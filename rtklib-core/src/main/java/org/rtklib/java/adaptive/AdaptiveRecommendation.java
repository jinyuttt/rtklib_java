package org.rtklib.java.adaptive;

import java.util.LinkedHashMap;
import java.util.Map;

public class AdaptiveRecommendation {
    private final long epoch;
    private final String context;
    private final Map<String, String> suggestions = new LinkedHashMap<>();

    public AdaptiveRecommendation(long epoch, String context) {
        this.epoch = epoch;
        this.context = context;
    }

    public void addSuggestion(String param, String value, String reason) {
        suggestions.put(param, value + " (" + reason + ")");
    }

    public long getEpoch() { return epoch; }
    public String getContext() { return context; }
    public Map<String, String> getSuggestions() { return suggestions; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ADAPTIVE-RECOMMEND] epoch=").append(epoch).append(" ").append(context).append("\n");
        sb.append("  建议（未执行）:\n");
        for (Map.Entry<String, String> e : suggestions.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }
}