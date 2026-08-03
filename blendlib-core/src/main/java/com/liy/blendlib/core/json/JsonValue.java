package com.liy.blendlib.core.json;

/** Internal strict-JSON tree used by the core loader without a runtime JSON dependency. */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {
}
