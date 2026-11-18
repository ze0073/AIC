package com.shijie.accessibility.hosting;
import java.util.*;
public final class HostedRunIdentity {
    private HostedRunIdentity(){}
    public static Map<?,?> hosting(Map<?,?> snapshot){return snapshot!=null&&snapshot.get("hosting") instanceof Map<?,?> m?m:Map.of();}
    public static boolean sameSite(Map<?,?> before,Map<?,?> after){var a=hosting(before);var b=hosting(after);return a.get("siteId")!=null&&Objects.equals(a.get("siteId"),b.get("siteId"));}
    public static boolean isRetest(Map<?,?> before,Map<?,?> after){return sameSite(before,after)&&Objects.equals(hosting(before).get("deploymentId"),hosting(after).get("baseDeploymentId"))&&hosting(after).get("sourceManifest")!=null;}
}
