package org.rtklib.java.adaptive;

public interface AdaptiveListener {
    void onSceneSwitch(AdaptiveEvent event);
    void onDegradation(AdaptiveEvent event);
    void onParamRecommendation(AdaptiveRecommendation rec);
    void onBaselineSwitch(AdaptiveEvent event);
}