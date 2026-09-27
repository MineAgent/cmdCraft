/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.example.httpd.HttpdProvider;
import com.example.httpd.PathHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.client.Minecraft;

/**
 * The mod's endpoints, mounted under MGHttpdProvider's shared server as {@code /op}.
 *
 * <ul>
 *   <li>{@code GET /op/} returns the manual.</li>
 *   <li>{@code POST /op/} runs one command (the request body) and returns its outcome.</li>
 * </ul>
 *
 * <p>The HTTP server itself (127.0.0.1:3420) belongs to MGHttpdProvider; this class only serves the
 * paths below the prefix, so {@code "/"} here means {@code /op}.</p>
 *
 * <p>Requests arrive on HTTP worker threads. Every command is hopped onto the client thread through
 * {@link Minecraft#execute} and the worker waits for the outcome, so a response always describes
 * what really happened: {@code 200} for success, {@code 400} for a rejected command, {@code 409}
 * when no world is loaded. Crafting keeps clicking over several client ticks; that request stays
 * open until the craft job reports back.</p>
 */
public final class OpEndpoint implements PathHandler {
	public static final String PREFIX = "/op";
	public static final String NAME = "Craft Command — 客户端容器操作 (合成/背包/熔炉/箱子/转向)";

	/** What the provider's {@code GET /} index lists for this mod. */
	public static final List<HttpdProvider.Endpoint> ENDPOINTS = List.of(
			new HttpdProvider.Endpoint("GET", "/op/", "使用说明"),
			new HttpdProvider.Endpoint("POST", "/op/", "执行命令 (text/plain, UTF-8)"));

	private static final Logger LOG = Logger.getLogger("craftcmd");
	private static final int MAX_BODY_BYTES = 16 * 1024;
	/** How long to wait for the command to even reach the client thread. */
	private static final long DISPATCH_WAIT_MS = 15_000L;
	/** Additional time a running craft job gets before the request is answered with 504. */
	private static final long CRAFT_WAIT_MS = 120_000L;

	@Override
	public void handle(HttpExchange exchange, String path) throws IOException {
		try {
			if (!"/".equals(path)) {
				respond(exchange, 404, "text/plain; charset=utf-8",
						"no endpoint at /op" + path + "\n\n" + Help.text());
				return;
			}

			switch (exchange.getRequestMethod()) {
				case "GET", "HEAD" -> respond(exchange, 200, "text/plain; charset=utf-8", Help.text());
				case "POST" -> handlePost(exchange);
				case "OPTIONS" -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 204, "text/plain; charset=utf-8", "");
				}
				default -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed: "
							+ exchange.getRequestMethod() + " (use GET for the manual, POST to run a command)\n");
				}
			}
		} catch (BodyTooLargeException e) {
			respond(exchange, 413, "text/plain; charset=utf-8",
					"request body too large (max " + MAX_BODY_BYTES + " bytes)\n");
		} catch (Exception e) {
			LOG.log(Level.WARNING, "request failed", e);
			respond(exchange, 500, "text/plain; charset=utf-8", "internal error: " + e + "\n");
		}
		// the provider closes the exchange
	}

	/** {@code POST /op/}: run the body as one command and answer with its outcome. */
	private void handlePost(HttpExchange exchange) throws IOException {
		String body = new String(readBody(exchange), StandardCharsets.UTF_8).trim();
		String line = normalize(body);

		if (line.isEmpty()) {
			respond(exchange, 400, "text/plain; charset=utf-8", "empty request body\n\n" + Help.text());
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client == null) {
			respond(exchange, 409, "text/plain; charset=utf-8", "game not ready: the Minecraft client is not running\n");
			return;
		}

		OpCommandSource source = new OpCommandSource();
		Runnable task = () -> OpCommands.dispatch(line, source, client);

		try {
			if (client.isSameThread()) {
				task.run();
			} else {
				client.execute(task);
			}
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "could not queue the command", e);
			respond(exchange, 503, "text/plain; charset=utf-8", "could not queue the command: " + e + "\n");
			return;
		}

		try {
			boolean finished = source.await(DISPATCH_WAIT_MS);

			if (!finished && source.isAsync()) {
				// A craft job is clicking its way through the grid; wait for it to report back.
				finished = source.await(CRAFT_WAIT_MS);
			}

			if (!finished) {
				respond(exchange, 504, "text/plain; charset=utf-8",
						"timed out waiting for the client (the command may still be running)\n" + source.text());
				return;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			respond(exchange, 503, "text/plain; charset=utf-8", "interrupted\n");
			return;
		}

		String text = source.text();

		if (source.notReady()) {
			respond(exchange, 409, "text/plain; charset=utf-8", text);
			return;
		}

		if (source.failed()) {
			respond(exchange, 400, "text/plain; charset=utf-8", text);
			return;
		}

		respond(exchange, 200, "text/plain; charset=utf-8", text.isEmpty() ? "ok\n" : text);
	}

	/**
	 * Accepts the body with a little slack: surrounding whitespace, a leading {@code /} and a legacy
	 * {@code cmdop} prefix are dropped, so {@code craft stick 4}, {@code /craft stick 4} and
	 * {@code /cmdop craft stick 4} all mean the same thing.
	 */
	private static String normalize(String body) {
		String line = body.trim();

		while (line.startsWith("/")) {
			line = line.substring(1).trim();
		}

		if (startsWithWord(line, "cmdop")) {
			line = line.substring("cmdop".length()).trim();
		}

		return line;
	}

	private static boolean startsWithWord(String text, String word) {
		return text.regionMatches(true, 0, word, 0, word.length())
				&& (text.length() == word.length() || Character.isWhitespace(text.charAt(word.length())));
	}

	private static byte[] readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			int total = 0;
			int read;

			while ((read = in.read(buffer)) > 0) {
				total += read;

				if (total > MAX_BODY_BYTES) {
					throw new BodyTooLargeException();
				}

				out.write(buffer, 0, read);
			}

			return out.toByteArray();
		}
	}

	private static void respond(HttpExchange exchange, int status, String contentType, String body)
			throws IOException {
		respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
			throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");

		if ("HEAD".equals(exchange.getRequestMethod()) || body.length == 0) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}

		exchange.sendResponseHeaders(status, body.length);

		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static final class BodyTooLargeException extends IOException {
		private static final long serialVersionUID = 1L;
	}
}
