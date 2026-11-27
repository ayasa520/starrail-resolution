package io.rikka.starrailresolution;

import org.json.JSONObject;

/** The UI depends on operations, independently of how their privilege was granted. */
interface PrivilegedGateway {
    JSONObject execute(String action,Integer width) throws Exception;
}
