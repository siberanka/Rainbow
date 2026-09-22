package com.siberanka.twilight.compiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

record ResolvedJavaModel(String identifier, JsonArray elements, Map<String, String> textures, JsonObject display,
                         boolean handheld) {
    boolean isThreeDimensional() { return elements != null && !elements.isEmpty(); }
}
