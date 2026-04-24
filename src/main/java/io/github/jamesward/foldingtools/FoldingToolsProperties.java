package io.github.jamesward.foldingtools;

public class FoldingToolsProperties {

    public static final String NAMESPACE = "spring.ai.folding-tools";

    private boolean enabled = true;
    private int maxSynthesizedTools = 64;
    private boolean dryRun = false;

    private final Listify listify = new Listify();
    private final Aggregate aggregate = new Aggregate();
    private final Observe observe = new Observe();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxSynthesizedTools() { return maxSynthesizedTools; }
    public void setMaxSynthesizedTools(int v) { this.maxSynthesizedTools = v; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public Listify getListify() { return listify; }
    public Aggregate getAggregate() { return aggregate; }
    public Observe getObserve() { return observe; }

    public static class Listify {
        private boolean enabled = true;
        private int maxListSize = 100;
        private Dispatch dispatch = Dispatch.SEQUENTIAL;
        private String parameterNameRegex = ".*(Id|Uuid|Key|Code)$";
        private String toolNameRegex = "(get|find|fetch|lookup|read|resolve).*";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxListSize() { return maxListSize; }
        public void setMaxListSize(int v) { this.maxListSize = v; }
        public Dispatch getDispatch() { return dispatch; }
        public void setDispatch(Dispatch d) { this.dispatch = d; }
        public String getParameterNameRegex() { return parameterNameRegex; }
        public void setParameterNameRegex(String r) { this.parameterNameRegex = r; }
        public String getToolNameRegex() { return toolNameRegex; }
        public void setToolNameRegex(String r) { this.toolNameRegex = r; }
    }

    public static class Aggregate {
        private boolean enabled = true;
        private int maxChainDepth = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxChainDepth() { return maxChainDepth; }
        public void setMaxChainDepth(int v) { this.maxChainDepth = v; }
    }

    public static class Observe {
        private boolean enabled = false;
        private int minObservations = 2;
        private int bufferSizePerSession = 256;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinObservations() { return minObservations; }
        public void setMinObservations(int v) { this.minObservations = v; }
        public int getBufferSizePerSession() { return bufferSizePerSession; }
        public void setBufferSizePerSession(int v) { this.bufferSizePerSession = v; }
    }

    public enum Dispatch { SEQUENTIAL, PARALLEL }
}
