package dev.mcpfabric.bridge;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcContextTest {
	private static RpcContext ctx(String json) {
		return new RpcContext("test", JsonParser.parseString(json).getAsJsonObject());
	}

	/** A caller's mistake is a bad_request naming the param, never an internal error. */
	private static void assertBadRequest(String param, Executable call) {
		RpcException e = assertThrows(RpcException.class, call);
		assertEquals("bad_request", e.code());
		assertTrue(e.getMessage().contains("'" + param + "'"), e.getMessage());
	}

	@Test
	void wrongTypesAreBadRequests() {
		assertBadRequest("x", () -> ctx("{\"x\": \"abc\"}").getDouble("x"));
		assertBadRequest("slot", () -> ctx("{\"slot\": 1.5}").getInt("slot"));
		assertBadRequest("slot", () -> ctx("{\"slot\": true}").getInt("slot"));
		assertBadRequest("sprint", () -> ctx("{\"sprint\": \"yes\"}").optBool("sprint", false));
		assertBadRequest("name", () -> ctx("{\"name\": {\"a\": 1}}").getString("name"));
		assertBadRequest("center", () -> ctx("{\"center\": 5}").optObject("center"));
		assertBadRequest("ids", () -> ctx("{\"ids\": \"minecraft:stone\"}").getStringList("ids"));
	}

	@Test
	void positionListsAreCheckedPerItem() throws RpcException {
		assertBadRequest("positions", () -> ctx("{}").getVec3List("positions", 4));
		assertBadRequest("positions", () -> ctx("{\"positions\": {\"x\": 1}}").getVec3List("positions", 4));
		assertBadRequest("positions", () -> ctx("{\"positions\": [{}, {}, {}]}").getVec3List("positions", 2));
		assertBadRequest("positions[1]", () -> ctx("{\"positions\": [{\"x\": 1, \"y\": 2, \"z\": 3}, 5]}").getVec3List("positions", 4));
		assertBadRequest("positions[0].y", () -> ctx("{\"positions\": [{\"x\": 1, \"z\": 3}]}").getVec3List("positions", 4));
		assertBadRequest("positions[0].x", () -> ctx("{\"positions\": [{\"x\": [], \"y\": 2, \"z\": 3}]}").getVec3List("positions", 4));
		var list = ctx("{\"positions\": [{\"x\": 1, \"y\": 2, \"z\": 3}, {\"x\": -4, \"y\": 0.5, \"z\": 9}]}").getVec3List("positions", 4);
		assertEquals(2, list.size());
		assertArrayEquals(new double[] {-4, 0.5, 9}, list.get(1));
	}

	@Test
	void nestedCoordinatesAreCheckedToo() throws RpcException {
		assertBadRequest("from.z", () -> ctx("{\"from\": {\"x\": 1, \"y\": 2}}").getVec3("from"));
		assertBadRequest("from.y", () -> ctx("{\"from\": {\"x\": 1, \"y\": \"up\", \"z\": 3}}").getVec3("from"));
		assertArrayEquals(new double[] {1, 2.5, -3}, ctx("{\"from\": {\"x\": 1, \"y\": 2.5, \"z\": -3}}").getVec3("from"));
	}

	@Test
	void missingRequiredParamsAreBadRequests() {
		assertBadRequest("uuid", () -> ctx("{}").getString("uuid"));
		assertBadRequest("uuid", () -> ctx("{\"uuid\": null}").getString("uuid"));
	}

	@Test
	void keepsAcceptingWhatGsonAcceptedBefore() throws RpcException {
		assertEquals(5, ctx("{\"slot\": \"5\"}").getInt("slot"));
		assertEquals(2.5, ctx("{\"r\": \"2.5\"}").getDouble("r"));
		assertEquals(true, ctx("{\"b\": \"TRUE\"}").optBool("b", false));
		assertEquals("5", ctx("{\"s\": 5}").getString("s"));
		assertEquals(7, ctx("{}").optInt("n", 7));
		assertNull(ctx("{}").optBoolean("b"));
		assertNull(ctx("{}").optVec3("center"));
	}
}
