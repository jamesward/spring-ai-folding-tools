# Decisions & lessons learned

A running log of decisions and facts that aren't self-evident from the
code. Forward-looking design belongs in `SPEC.md`; this file is the
engineering log. Append rather than rewrite.

## Build & runtime

- **Spring AI 1.1.0** (released, not snapshot). Requires
  `repo.spring.io/milestone` in `<repositories>` — central alone isn't
  enough for the BOM at time of writing.
- **Java 21**. Records in `FoldingContext`, `FoldingObservation`,
  pattern-matching `instanceof` in the advisor.
- **Maven**, single module. Reason: Spring AI itself is Maven; single
  module keeps v0 cheap. Split into `core` + `listify` + `aggregate` +
  `observe` + `autoconfigure` only when there's cross-module churn to
  pay for it.
- **Group `io.github.jamesward`, package
  `io.github.jamesward.foldingtools`**. GitHub-anchored group id since
  there's no owned domain; package matches 1:1.

## Spring AI 1.1.0 API reference (what we verified via javap)

Worth recording because these shapes shift between minor versions and
re-deriving them from docs/jars burns a lot of time.

- `CallAdvisor.adviseCall(ChatClientRequest, CallAdvisorChain) -> ChatClientResponse`
- `StreamAdvisor.adviseStream(ChatClientRequest, StreamAdvisorChain) -> Flux<ChatClientResponse>`
- `Advisor extends org.springframework.core.Ordered` and adds `getName()`.
- `ChatClientRequest` is a **record** with `prompt()` and
  `context()` (`Map<String,Object>`). Builder: `.prompt().context(Map).build()`.
- `Prompt.getOptions()` returns `ChatOptions` (interface).
  `ToolCallingChatOptions extends ChatOptions` and is **mutable** —
  `setToolCallbacks(List)` and `setToolContext(Map)` edit in place.
  Lean on this rather than rebuilding a new Prompt.
- `ToolCallback.getToolDefinition() -> ToolDefinition`, `call(String)`,
  default `call(String, ToolContext)`.
- `ToolDefinition` has only `name()`, `description()`, `inputSchema()`
  (schema is a **JSON string**). **No output schema** — that's the
  key constraint driving Strategy 2's design.
- `ToolDefinition.builder()` returns `DefaultToolDefinition.Builder`
  with `.name().description().inputSchema().build()`.
- `ToolCallingManager.resolveToolDefinitions(ToolCallingChatOptions)`
  and `executeToolCalls(Prompt, ChatResponse) -> ToolExecutionResult`.
- `ToolExecutionResult.conversationHistory() -> List<Message>`.
  The tool calls show up as `ToolResponseMessage` with
  `getResponses() -> List<ToolResponse>` (each has `id()`, `name()`,
  `responseData()`).
- `AssistantMessage.hasToolCalls()`, `getToolCalls() -> List<ToolCall>`
  where `ToolCall` is a record of `(id, type, name, arguments)`.
- `ChatMemory.CONVERSATION_ID` lives in **`spring-ai-model`**, not
  `spring-ai-client-chat`. Easy to miss.

## Integration decisions

- **Two hooks, not one.** `FoldingToolsAdvisor` (a `CallAdvisor`) does
  outbound tool-list rewriting; `ObservingToolCallingManager` (a
  `ToolCallingManager` decorator wired into `ToolCallAdvisor`) does
  per-tool-call observation. Splitting these avoids re-implementing the
  tool loop while keeping observation at the correct boundary.
- **Advisor order** `Ordered.HIGHEST_PRECEDENCE + 1000`. Low enough to
  run before any user advisor, `ToolCallAdvisor`, and
  `ToolSearchToolCallAdvisor`; high enough that user overrides can
  squeeze in ahead.
- **Session id plumbing.** `FoldingToolsAdvisor` resolves the session
  id once (default: `ChatMemory.CONVERSATION_ID` from advisor context),
  writes it into `ToolCallingChatOptions.toolContext` under
  `ObservingToolCallingManager.SESSION_ID_KEY`. The manager reads it
  from there per call. This is the only channel that survives from
  advisor context to `ToolCallingManager.executeToolCalls`, which only
  sees `Prompt` + `ChatResponse`.
- **No implicit fallback for session id.** If `CONVERSATION_ID` isn't
  set and no custom `SessionIdResolver` is registered, Strategy 3 is a
  no-op. A `ChatClient`-scoped fallback would silently cross-
  contaminate users.
- **Synthesized tools dispatch directly, not via `ToolCallingManager`.**
  `SynthesizedToolCallback.call()` invokes underlying `ToolCallback`s
  as plain Java methods. Consequence: the observing manager sees only
  model-initiated calls, which is the right signal for Strategy 3's
  pattern detector — we already know our own composites.
- **Mutate `ToolCallingChatOptions` in place.** The alternative (rebuild
  the `Prompt` + `ChatClientRequest`) works but is fragile across
  provider-specific `ChatOptions` subtypes that carry extra state. The
  existing options instance already owns the full config surface.

## Strategy 1 (AutoListify) decisions

- **One parameter listified per tool.** Multi-param listification is a
  cartesian-product hazard and v0 can't distinguish "cross product" from
  "zip." Opt-in only if we later expose it.
- **Sequential dispatch only.** The `PARALLEL` enum value is wired in
  config but not consulted. Parallel needs a bounded executor, rate-
  limiting awareness, and a story for cancellation — not worth v0.
- **`<name>Batch` tool naming.** Simple and collision-resistant. Ruled
  out pluralization ("getCustomers") because of collision with existing
  plural lookups.
- **Listified parameter naming.** `<name>s`, or `<name>List` if
  already ends in `s`. Easy rule, readable in schemas.
- **Partial-failure format.** Each entry is `{input, output}` on
  success or `{input, error}` on failure. Never `{input, output, error}`
  — unambiguous for the model.
- **De-duplicate inputs before dispatch.** Saves underlying calls;
  aligns with lookup semantics (asking for the same id twice is never
  what you want).
- **Only listify top-level scalar properties.** Nested objects and
  `$ref` skipped. The parameter-name regex is applied at the top-level
  `properties` map only.
- **Schema manipulation via Jackson.** Spring AI pulls in
  jackson-databind transitively; using it directly keeps the dependency
  graph flat.

## Strategy 2 (AutoAggregate) decisions

- **Two binding sources, hints win.** `AggregationHint` (explicit, works
  for any `ToolCallback`) takes precedence over auto-matching. If a
  hint exists for a pair, the auto-match path skips it — avoids
  double-synthesis.
- **Auto-match only for `AggregatableToolCallback` sources.** Without
  output-schema info we cannot safely guess a binding. Tools that
  don't carry output schema are still aggregatable via hints.
- **Unique match required.** The target tool must have exactly one
  required scalar parameter whose **name and JSON type** both match a
  scalar field in the source's output schema. Multi-required or
  non-scalar ⇒ skip. Multi-candidate target set is allowed (each
  unique-matching target becomes its own aggregate).
- **Name+type match, not just name.** Rejects `{customerId: string}` ↔
  `{customerId: number}` collisions.
- **Target with unsatisfied required params is skipped.** If the bound
  parameter doesn't cover *all* `required`, we can't synthesize a
  single-input aggregate. Hints that want to cover multi-required
  targets will need a later "hint with defaults" extension.
- **Aggregate tool shape.** Input = source's input schema verbatim;
  output = `{"source": <src_out>, "target": <tgt_out>}` on success,
  `{"source": <src_out>, "error": "..."}` on target failure, or
  `{"error": "...source failed..."}` when source fails (no partial
  result).
- **Naming.** `<source>Then<Target>` with the target capitalized. Clash
  handling is "first one wins" within a single fold pass;
  `FoldingToolsAdvisor`'s outer budget/dedup handles cross-strategy
  collisions.
- **Chain depth 1 only.** A → B, no A → B → C in v0. Also: synthesized
  callbacks are ignored as inputs to further aggregation passes —
  prevents exponential blow-up if the advisor re-runs.
- **Jackson ObjectMapper shared static.** Thread-safe by contract;
  avoids per-call allocation hot path.

## Strategy 3 (ObserveAndCreate) decisions

- **Reuses `ChainSynthesizer`.** Strategy 2 and Strategy 3 emit the
  same shape of synthesized tool (two-step A → B); only the binding
  source differs. Extracting `ChainSynthesizer` keeps both strategies
  small and avoids two copies of the dispatch logic drifting apart.
- **Match = exact JSON value equality.** A binding is counted only
  when the actual value in the later tool's args equals the value at
  some top-level field in the earlier tool's result. Stronger signal
  than name-matching alone (rules out coincidental field names); v0
  accepts this at the cost of missing bindings that went through
  string formatting / type coercion.
- **Top-level fields only.** Nested extraction is deferred — would
  need a path notation, which enlarges the hint/binding shape.
- **No cross-session mixing.** The strategy operates on observations
  already filtered to the current session by `FoldingToolsAdvisor` —
  the strategy itself is session-agnostic. Keeping session scoping at
  the advisor is important so per-session eviction in the store works.
- **Synthesized-tool observations ignored.** Tools present in
  `sourceTools` as a `SynthesizedToolCallback` get their names added
  to a skip set; observations of those tools don't feed the pattern
  detector. Prevents learning composites of our own composites.
- **Observations of unknown tools ignored.** A tool that was observed
  earlier but is no longer in `sourceTools` can't be composed anyway,
  so its observations are dropped at parse time.
- **Self-loops skipped.** A pair (A, A) is skipped before field
  matching runs — saves work and rules out "ping echoes its token"
  degenerate patterns.
- **`min=2` default, 1 allowed via config.** Two occurrences is the
  threshold from the spec. Config lets tests and paranoid prod
  settings tune either direction; 0 is clamped to 1 defensively.
- **`O(N²)` pair scan is fine for v0.** Per-session observation buffer
  is bounded (default 256); the scan runs once per turn. If this shows
  up in profiles later, candidate optimizations are: hash the later
  tool's arg values first, then stream earlier tools' result fields
  through the hash.
- **Produced tools live only for the current turn's fold pass.** The
  advisor re-runs folding each turn, so observations accumulate in the
  store and promotions get re-derived. No separate "promoted tools
  cache" in v0. A `FoldingToolsStore` impl (out-of-scope) could cache
  promotions durably; the current design leaves that door open.

## v0 non-goals (will need design work before we attempt them)

- **`ToolContext` propagation into synthesized dispatch.**
  `SynthesizedToolCallback.call(String, ToolContext)` currently
  delegates to the no-context overload. Once real tools start reading
  session-scoped context (e.g. tenant id), we need to pass it through
  every underlying invocation.
- **Parallel dispatch in AutoListify.** Needs a configurable
  `Executor` + bounded concurrency + per-tool opt-in.
- **Persistent / cross-session learning** for Strategy 3. The SPI hole
  exists (`SessionObservationStore`) but no durable impl ships in v0.
- **Spring Boot auto-configuration.** Keeping `core` pure-Java until
  the SPIs stabilize.
- **Cost-aware folding.** Needs measured token/latency history.
- **Folding across MCP tool servers.** Blocked on Spring AI's MCP
  introspection story being stable.

## Open constraints to be aware of

- **No output schema on `ToolDefinition`.** Confirmed via javap against
  `spring-ai-model-1.1.0.jar`: the interface exposes only `name()`,
  `description()`, `inputSchema()`. `MethodToolCallback`'s constructor
  takes a `java.lang.reflect.Method` but **does not expose a getter**
  for it — no reflection-free way to recover the return type.
  `FunctionToolCallback` carries an `inputType` but no output-type
  getter either.
- **Consequences for Strategy 2 (taken):**
  - Define our own `AggregatableToolCallback extends ToolCallback` with
    `outputSchema()`. Tool authors who want automatic aggregation
    implement it (or wrap their callback).
  - Support explicit `AggregationHint` records as the primary binding
    mechanism — works for any `ToolCallback`, including MCP / method /
    function tools, without code changes to the source tools.
  - Auto-match (via `AggregatableToolCallback`) is conservative:
    requires a **unique** name+type match between source output fields
    and target required scalar params. Ambiguity ⇒ skip, not guess.
  - Chain depth capped at **1** in v0 (A → B only). Longer chains
    explode combinatorially and the SPI for declaring them cleanly
    isn't settled yet.
