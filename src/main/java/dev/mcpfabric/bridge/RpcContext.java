package dev.mcpfabric.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed, null-safe accessor over the {@code params} object of an RPC call.
 *
 * <p>A param of the wrong type (a string where a number is expected, an object where a boolean is,
 * ...) is the caller's mistake, so every accessor reports it as {@code bad_request} naming the param,
 * never as an internal error.
 */
public final class RpcContext {
	private final String method;
	private final JsonObject params;

	public RpcContext(String method, JsonObject params) {
		this.method = method;
		this.params = params == null ? new JsonObject() : params;
	}

	public String method() {
		return method;
	}

	public JsonObject params() {
		return params;
	}

	public boolean has(String key) {
		return params.has(key) && !params.get(key).isJsonNull();
	}

	// --- required ----------------------------------------------------------------------------

	public String getString(String key) throws RpcException {
		require(key);
		return asString(key, params.get(key));
	}

	public int getInt(String key) throws RpcException {
		require(key);
		return asInt(key, params.get(key));
	}

	public double getDouble(String key) throws RpcException {
		require(key);
		return asDouble(key, params.get(key));
	}

	public JsonObject getObject(String key) throws RpcException {
		require(key);
		if (!params.get(key).isJsonObject()) throw RpcException.badRequest("Param '" + key + "' must be an object.");
		return params.getAsJsonObject(key);
	}

	/** A required {@code {x, y, z}} object param, as {x, y, z}. */
	public double[] getVec3(String key) throws RpcException {
		return vec3(key, getObject(key));
	}

	/** A required array of {@code {x, y, z}} objects (at most {@code max}), each as {x, y, z}. */
	public List<double[]> getVec3List(String key, int max) throws RpcException {
		require(key);
		if (!params.get(key).isJsonArray()) throw RpcException.badRequest("Param '" + key + "' must be an array of {x,y,z} objects.");
		JsonArray a = params.getAsJsonArray(key);
		if (a.size() > max) throw RpcException.badRequest("Param '" + key + "' takes at most " + max + " positions.");
		List<double[]> out = new ArrayList<>(a.size());
		for (int i = 0; i < a.size(); i++) {
			String item = key + "[" + i + "]";
			JsonElement e = a.get(i);
			if (e == null || !e.isJsonObject()) throw RpcException.badRequest("Param '" + item + "' must be an {x,y,z} object.");
			out.add(vec3(item, e.getAsJsonObject()));
		}
		return out;
	}

	// --- optional ----------------------------------------------------------------------------

	public String optString(String key, String def) throws RpcException {
		return has(key) ? asString(key, params.get(key)) : def;
	}

	public int optInt(String key, int def) throws RpcException {
		return has(key) ? asInt(key, params.get(key)) : def;
	}

	public long optLong(String key, long def) throws RpcException {
		if (!has(key)) return def;
		double d = asDouble(key, params.get(key));
		if (d != Math.rint(d) || Math.abs(d) > 9.007199254740991E15) throw wrongType(key, "an integer");
		return (long) d;
	}

	public double optDouble(String key, double def) throws RpcException {
		return has(key) ? asDouble(key, params.get(key)) : def;
	}

	public boolean optBool(String key, boolean def) throws RpcException {
		return has(key) ? asBool(key, params.get(key)) : def;
	}

	public Boolean optBoolean(String key) throws RpcException {
		return has(key) ? asBool(key, params.get(key)) : null;
	}

	public JsonObject optObject(String key) throws RpcException {
		if (!has(key)) return null;
		if (!params.get(key).isJsonObject()) throw RpcException.badRequest("Param '" + key + "' must be an object.");
		return params.getAsJsonObject(key);
	}

	/** An optional {@code {x, y, z}} object param, as {x, y, z}, or null when absent. */
	public double[] optVec3(String key) throws RpcException {
		JsonObject o = optObject(key);
		return o == null ? null : vec3(key, o);
	}

	public List<String> getStringList(String key) throws RpcException {
		List<String> out = new ArrayList<>();
		if (!has(key)) return out;
		if (!params.get(key).isJsonArray()) throw RpcException.badRequest("Param '" + key + "' must be an array of strings.");
		JsonArray a = params.getAsJsonArray(key);
		for (JsonElement e : a) {
			if (!e.isJsonNull()) out.add(asString(key, e));
		}
		return out;
	}

	// --- conversions ---------------------------------------------------------------------------

	private static double[] vec3(String key, JsonObject o) throws RpcException {
		double[] v = new double[3];
		String[] axes = {"x", "y", "z"};
		for (int i = 0; i < 3; i++) {
			String field = key + "." + axes[i];
			JsonElement e = o.get(axes[i]);
			if (e == null || e.isJsonNull()) throw RpcException.badRequest("Missing required param '" + field + "'.");
			v[i] = asDouble(field, e);
		}
		return v;
	}

	private static String asString(String key, JsonElement e) throws RpcException {
		if (e.isJsonPrimitive()) return e.getAsString();
		throw wrongType(key, "a string");
	}

	private static int asInt(String key, JsonElement e) throws RpcException {
		double d = asDouble(key, e);
		if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) throw wrongType(key, "an integer");
		return (int) d;
	}

	/** A number, or a string holding one ("5"), as Gson accepted before. */
	private static double asDouble(String key, JsonElement e) throws RpcException {
		try {
			if (e.isJsonPrimitive() && !e.getAsJsonPrimitive().isBoolean()) {
				double d = e.getAsDouble();
				if (Double.isFinite(d)) return d;
			}
		} catch (NumberFormatException ignored) {
			// not a number: reported below
		}
		throw wrongType(key, "a number");
	}

	/** A boolean, or the strings "true"/"false". Gson read any other string as false. */
	private static boolean asBool(String key, JsonElement e) throws RpcException {
		if (e.isJsonPrimitive()) {
			if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
			String s = e.getAsString();
			if (s.equalsIgnoreCase("true")) return true;
			if (s.equalsIgnoreCase("false")) return false;
		}
		throw wrongType(key, "a boolean");
	}

	private static RpcException wrongType(String key, String type) {
		return RpcException.badRequest("Param '" + key + "' must be " + type + ".");
	}

	private void require(String key) throws RpcException {
		if (!has(key)) throw RpcException.badRequest("Missing required param '" + key + "'.");
	}
}
