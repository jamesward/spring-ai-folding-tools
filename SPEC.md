# spring-ai-folding-tools — Spec (draft)

A Spring AI library that "folds" a set of registered tools into a smaller,
higher-leverage set exposed to the model. Folding happens transparently via
an `Advisor` that rewrites the tool list on each chat request and unwraps
calls to synthesized tools back into the underlying real tools.

Goal: fewer round-trips, fewer tokens spent on tool schemas and tool-call
boilerplate, and less opportunity for the model to make sequencing
mistakes that a deterministic composition could avoid.

## Non-goals (for v0)

- Replacing the model's reasoning about *which* tools to use.
- Durable/shared learning across users or processes (observe-and-create is
  per-session only in v0).
- Streaming/partial results from folded tools.
- Cross-provider tool-schema quirks beyond what Spring AI already abstracts.

## Integration surface

We need two distinct hooks into Spring AI, and the framework gives us
clean ones for each:

1. **Outbound tool-list rewriting.** A `CallAdvisor` (and `StreamAdvisor`)
   whose `adviseCall(ChatClientRequest, CallAdvisorChain)` mutates the
   request's `ChatOptions.toolCallbacks` before delegating. This is where
   Strategies 1 and 2 add synthesized `ToolCallback`s, and where
   Strategy 3 attaches session-promoted tools on subsequent turns.

2. **Per-tool-call observation.** Spring AI 1.1 ships a recursive
   `ToolCallAdvisor` that pulls the tool-calling loop out of each
   `ChatModel` and into the advisor chain, dispatching via a
   configurable `ToolCallingManager`. We wire in an
   `ObservingToolCallingManager` that wraps the default manager and
   records `(toolName, args, result)` for Strategy 3's pattern detector.
   No custom loop to maintain — just a decorator on the manager.

Synthesized tools don't need any special dispatch support. Each
`SynthesizedToolCallback` holds references to the underlying real
`ToolCallback`s and does the fan-out / composition in its own `call()`.
The framework's default `ToolCallingManager` will look it up by name
and invoke it like any other tool.

### Ordering in the advisor chain

Spring AI advisors run in `getOrder()` order (lowest first on the
request side). Our placement:

- `FoldingToolsAdvisor` — low order, runs before `ToolCallAdvisor`.
  Mutates `ChatOptions.toolCallbacks` once per turn; sees the final
  response on the way back.
- `ToolCallAdvisor` — built with our `ObservingToolCallingManager`.
  Because it is recursive (`chain.copy(this).nextCall(...)`), each
  tool round-trip re-drives downstream advisors, so observation and
  any future per-round-trip behavior remain composable.

### Artifacts we'll produce

- `FoldingToolsAdvisor implements CallAdvisor, StreamAdvisor`
- Strategy SPI: `FoldingStrategy { List<ToolCallback> fold(FoldingContext) }`
  with implementations `AutoListifyStrategy`, `AutoAggregateStrategy`,
  `ObserveAndCreateStrategy`
- `SynthesizedToolCallback` — a `ToolCallback` whose `ToolDefinition` is
  programmatically built (name, description, JSON-schema string) and
  whose `call(String)` delegates to underlying callbacks
- `ObservingToolCallingManager implements ToolCallingManager` — wraps
  the default; feeds observations into a per-session buffer
- `FoldingToolsProperties` + Spring Boot auto-configuration; strategies
  independently toggleable

```
ChatClient
  └── advisors (in order)
        ├── FoldingToolsAdvisor              ← Strategies 1 & 2 inject
        │     → rewrites ChatOptions.toolCallbacks
        │       · adds listified variants
        │       · adds static aggregates
        │       · adds session-promoted tools (from Strategy 3)
        ├── ... user advisors ...
        └── ToolCallAdvisor
              └── ObservingToolCallingManager ← Strategy 3 observes
                    → default ToolCallingManager
                      · dispatches synthesized tools (they fan out internally)
                      · dispatches real tools (observations recorded)
```

### Reuse of Spring AI internals

- **JSON-schema generation:** reuse Spring AI's existing schema generator
  (the one behind `FunctionToolCallback` / `MethodToolCallback`) so
  synthesized schemas match the dialect the providers expect. For
  listified/aggregated schemas, start from the generator's output for
  the underlying type and transform it (wrap in array, union of fields)
  rather than rolling our own generator.
- **Tool construction:** prefer `FunctionToolCallback.builder(...)` /
  a `ToolDefinition.builder()` path for synthesized tools rather than a
  hand-rolled `ToolCallback` subclass, so we inherit future framework
  changes (e.g. metadata, approval hooks) for free.

## Strategy 1 — Auto listification

**Problem.** A tool like `getCustomer(customerId: String): Customer` forces
the model to emit N separate tool calls to fetch N customers. That is N
round-trips of schema + call + result framing.

**What we do.** For each tool parameter flagged as "lookup-scalar",
synthesize a sibling tool whose schema replaces the scalar with an array.
Both the original and the listified version are exposed; the model may
pick whichever fits.

- Original: `getCustomer(customerId: String) -> Customer`
- Synthesized: `getCustomers(customerIds: List<String>) -> List<Customer>`

**How a parameter qualifies as lookup-scalar.** In order of precedence:

1. Explicit annotation (`@Foldable(listify = true)` on the tool method
   parameter, or a programmatic `ToolSpec` hint).
2. Heuristics, gated by config:
   - parameter name matches `*Id`, `*Key`, `*Uuid`, `*Code`;
   - parameter type is `String`, `UUID`, a numeric primitive, or an enum;
   - the tool's return type is a single object (not already a collection);
   - the parameter is not otherwise constrained (e.g. pageable, cursor).
3. Opt-in by tool name pattern (configurable allow/deny list).

**Execution.** The synthesized tool fans out to the underlying tool:

- Dispatch mode is configurable: `SEQUENTIAL` (default, safest) or
  `PARALLEL` (bounded executor).
- Per-item failures are surfaced in the aggregated result as
  `{ id, error }` entries rather than failing the whole call, so the
  model can reason about partial success. Behavior is pluggable.
- Deduplicates input IDs before dispatch.

**Schema.** Description is derived from the original tool's description
with a short suffix like "Accepts a list of IDs; returns results in input
order." Naming convention is configurable (default: pluralize or append
`Batch`).

**Risks / open questions.**

- Some "lookup" tools have side effects; listifying them changes ordering
  semantics. Default to opt-in for non-`GET`-like tools.
- Result size blowup — cap the list length in the schema (configurable).

## Strategy 2 — Auto aggregation

**Problem.** If tool A returns a `Customer` and tool B takes a
`customerId`, the model has to call A, read the id out of the result, and
call B. A deterministic composition removes a turn.

**What we do.** Build a type graph across registered tools and synthesize
aggregate tools for obvious chains.

- Nodes: tool inputs and outputs, described by their Java types (and the
  JSON-schema view where types are structural).
- Edge A→B exists when B has a parameter that can be populated from A's
  output. Population rules:
  - exact type match on a field (`Customer.id` → `customerId: String` is
    matched by name + type);
  - single-field record/POJO satisfied by A's scalar return;
  - user-declared binding via `@Foldable(feedsInto = "B.customerId")` or
    programmatic mapping.
- Chains are bounded (default max depth = 2, i.e. A → B only; configurable
  up to some small N).
- Ambiguity (multiple A's can feed B) disables synthesis for that edge
  unless disambiguated by annotation.

**Synthesized tool shape.** For A: `findCustomer(email) -> Customer` and
B: `listOrders(customerId) -> List<Order>`, we emit:

- `findCustomerOrders(email) -> { customer: Customer, orders: List<Order> }`

Defaults:

- Input schema = union of upstream params that aren't satisfied
  internally.
- Output schema = struct of each step's output (configurable: last-only
  vs. all-steps). All-steps is the default because it preserves info the
  model might still need.
- Name derived from step names; collision-safe with a suffix.

**Execution.** Deterministic, sequential; a failure mid-chain returns a
structured error indicating which step failed and with what inputs, so
the model can fall back to the un-folded tools.

**Risks / open questions.**

- Combinatorial explosion of synthesized tools. Mitigations: depth cap,
  per-tool opt-in, a budget on total synthesized tools per request, and
  dropping aggregates whose base tools the model hasn't used recently.
- Semantic mismatches that types don't catch (two unrelated `String` IDs
  that happen to match by name). Name+type matching is the default;
  annotation overrides are strongly recommended for production.

## Strategy 3 — Observe and create

**Problem.** Some folding opportunities are only visible at runtime —
e.g. the model repeatedly calls `search → get → enrich` in that order.
Static analysis (Strategy 2) misses this if the types don't line up
cleanly, or if the useful "fold" is a specific argument pattern.

**What we do.** Passively record tool-call sequences within a session
and, when a sequence repeats with the data-flow shape of an aggregate,
synthesize a new tool and expose it on subsequent turns.

- Scope in v0: per-`ChatClient` conversation / memory scope. No
  cross-session or cross-user propagation.
- Observation: wrap each tool call to capture `(toolName, args, result,
  timestamp, turnIndex)`. Keep this in an in-memory buffer keyed by
  conversation id; bounded size; evicted with the conversation.
- Pattern detector: looks for sequences of length ≥ 2 where later calls
  consume fields that earlier calls produced. Requires a minimum number
  of observations (default 2) before promoting to a tool.
- Synthesis: same machinery as Strategy 2 but driven by an observed
  binding rather than a static type match. Because observation proves
  the binding works at least once, we can tolerate looser type matches.
- Lifecycle: the synthesized tool is attached to the session's advisor
  state; it vanishes when the session ends unless a
  `FoldingToolsStore` is wired up (out of scope for v0, but the API
  should leave room).

**Risks / open questions.**

- Privacy: tool arguments and results may contain user data. The
  observation buffer must respect any redaction the app already applies,
  and should be explicitly opt-in.
- Overfitting to coincidental sequences. Require N observations; only
  promote bindings where the later call's argument *value* came from the
  earlier call's result (not from the user prompt).
- Surfacing to the model: a freshly promoted tool won't be in the system
  prompt's tool list until the next turn. That's fine; call out in docs.
- Determinism / reproducibility: two identical sessions may end up with
  different tool sets. Provide a debug log of what was synthesized and
  why.

## Cross-cutting concerns

- **Config.** Each strategy toggleable; global caps on synthesized tool
  count; per-tool opt-in/opt-out annotation.
- **Observability.** Structured logs + Micrometer metrics per strategy:
  synthesized-tool count, invocation count, underlying-call fan-out,
  failures. A `/actuator/folding-tools` endpoint (optional) listing the
  current synthesized tools and their provenance.
- **Safety.** Synthesized tools are never exposed if the underlying
  tool is marked non-idempotent unless explicitly allowed.
- **Testing.** Each strategy gets unit tests against a fake
  `ToolCallback` set and an integration test against Spring AI's
  `ChatClient` with a recorded model.

## Module layout (tentative)

```
spring-ai-folding-tools/
  core/           // strategy interfaces, Advisor, synthesized ToolCallback
  listify/        // Strategy 1
  aggregate/      // Strategy 2 (static type-graph)
  observe/        // Strategy 3 (runtime observer + dynamic synthesis)
  autoconfigure/  // Spring Boot starter
  samples/        // runnable examples
```

## Out for v0, flagged for later

- Persistent, cross-session learning for Strategy 3.
- Cost-aware folding (prefer folds that reduce token or latency cost
  most, based on measured history).
- Folding across remote tool servers (MCP) once Spring AI's MCP
  integration is stable enough to introspect.
- Model-assisted naming/descriptions for synthesized tools.

## Open design questions

Resolved (see "Integration surface"):

- ~~Advisor-chain placement.~~ `CallAdvisor` for outbound tool-list
  rewriting, ordered before `ToolCallAdvisor`; an
  `ObservingToolCallingManager` plugged into `ToolCallAdvisor` for
  per-call observation.
- ~~Schema generation.~~ Reuse Spring AI's built-in generator (the one
  behind `FunctionToolCallback`) and transform its output rather than
  building our own.

### 1. Session identity for Strategy 3 — resolved

Decision: default to `ChatMemory.CONVERSATION_ID` (the standard advisor-
context key Spring AI memory advisors already populate); if it's absent,
Strategy 3 is a no-op for that request and we log a one-shot warning.

- SPI: `SessionIdResolver { Optional<String> resolve(ChatClientRequest) }`.
- Default resolver reads `ChatMemory.CONVERSATION_ID` from the advisor
  context.
- No fallback to "the `ChatClient` instance" — that would silently
  cross-contaminate observations between users. Honest failure beats
  privacy-unsafe defaults.
- Apps that don't use `ChatMemory` can register a custom resolver
  (e.g. pulling from `RequestAttributes`, an explicit header, etc.) to
  opt in.
- The session key is also what the promoted-tools store is keyed on, so
  overriding it automatically relocates the learning scope.

### 2. Interaction with `ToolCallAdvisor`'s recursion — resolved

Decision: no design change needed; validated via test. Rationale:

- `ToolCallAdvisor` relies on
  `ToolCallingChatOptions.internalToolExecutionEnabled(false)` and
  reuses the same `ChatOptions` (and therefore the same
  `toolCallbacks` list) across loop iterations. Our synthesized tools
  are added once in `FoldingToolsAdvisor` and stay attached.
- A `SynthesizedToolCallback` invokes underlying real `ToolCallback`s
  directly (plain Java method calls), not through the
  `ToolCallingManager`. So the `ObservingToolCallingManager` sees only
  the tools the *model* requested, synthesized or real — which is
  exactly the signal Strategy 3 wants. The pattern detector filters out
  synthesized-tool observations (we already know their composition).
- Convert this into a concrete test plan instead of a design question:
  an integration test that runs ≥ 3 tool round-trips with one
  synthesized tool in play and asserts (a) it remains visible in each
  iteration's request, (b) it isn't duplicated, (c) observation records
  only the model-initiated calls.

### 3. Heuristic calibration for Strategy 1 — resolved (starting rules + plan)

Starting defaults (conservative, explicitly opt-in beyond this):

- Parameter-name regex: `.*(Id|Uuid|Key|Code)$` (case-insensitive).
- Parameter types allowed: `String`, `UUID`, numeric primitives/boxed,
  enums.
- Tool-name regex for automatic listification: `(get|find|fetch|lookup|read|resolve).*`.
  Any other shape requires an explicit `@Foldable(listify = true)`.
- Max list size in synthesized schema: 100.
- Dispatch: `SEQUENTIAL` by default; `PARALLEL` requires explicit
  opt-in per tool.

Calibration plan:

- Reference corpora: (a) `spring-ai-community/spring-ai-tool-search-tool`
  demo's synthetic tool set (public, large, diverse), (b) the GitHub MCP
  tool set exposed in this environment (real-world, lookup-heavy),
  (c) one Spring AI sample app with classic CRUD tools.
- Metrics to track per corpus: listify candidates identified,
  false-positive rate (listified tools whose semantics change under
  batching — needs human review), false-negative rate (genuine
  candidates missed).
- Ship v0 with the defaults above and a `--dry-run` mode in the
  advisor that logs candidates without registering them, so users can
  tune against their own corpus.

### 4. Interplay with dynamic tool search — resolved

Dynamic tool discovery is already a recursive advisor
(`ToolSearchToolCallAdvisor` in
`spring-ai-community/spring-ai-tool-search-tool`). It indexes the full
tool catalog, exposes only a `tool_search` tool to the model, and
materializes matched tools on subsequent turns.

Decision: folding and tool search are complementary — fold first,
search second.

- `FoldingToolsAdvisor` runs at a low order (before
  `ToolSearchToolCallAdvisor`). Strategies 1 and 2 produce synthesized
  `ToolCallback`s that are added to the full catalog the search indexer
  sees, so the model can discover them via `tool_search` like any
  other tool.
- Strategy 3 promotions are also handed to the indexer (via the same
  advisor context / provider extension point), scoped to the session
  key from question #1. Retrieval and folding both benefit from the
  session boundary.
- If an app uses tool search, folding's value shifts: Strategies 1 & 2
  reduce the round-trip count *once a fold is selected*; they don't
  directly reduce the in-prompt token count (tool search already does
  that). Strategy 3's value grows, because patterns observed under a
  retrieved subset still promote useful composites.
- Document the recommended order
  (`FoldingToolsAdvisor` < `ToolSearchToolCallAdvisor` <
  `ToolCallAdvisor`) and provide a Spring Boot auto-configuration that
  sets it when both libraries are on the classpath.

### Remaining (genuinely open)

- Whether `FoldingToolsStore` (cross-session persistence for Strategy 3)
  should ship as a v0.x extension module or wait for real demand. Leave
  the SPI hole, decide later.
- Exact shape of the `@Foldable` annotation (method- vs parameter-level,
  interaction with `@Tool` / `@ToolParam`). Needs a dry run against a
  real tool class before finalizing.
