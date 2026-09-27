/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;

/**
 * Per-request state for one {@code POST /op/} request: the messages the command produced, whether it
 * failed, and the signal that tells the HTTP thread the request is over.
 *
 * <p>It replaces the chat feedback of the old client commands. Every command runs on the client
 * (render) thread and only appends text here, so the HTTP thread can build the response without
 * touching the game.</p>
 *
 * <p>Commands normally finish before the dispatcher returns and the dispatcher completes the
 * request. The one exception is {@code craft}: it starts a job that keeps clicking container slots
 * on the following client ticks, so it calls {@link #expectAsync()} and only completes the request
 * when the job finishes or aborts.</p>
 */
public final class OpCommandSource {
	/** Upper bound so a runaway command cannot grow the response without limit. */
	private static final int MAX_MESSAGES = 64;

	private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
	private final CountDownLatch done = new CountDownLatch(1);
	private volatile boolean failed;
	private volatile boolean notReady;
	private volatile boolean async;

	/** Success feedback; the HTTP response is {@code 200} unless an error was also reported. */
	public void sendFeedback(Component message) {
		add(message.getString(), false);
	}

	/** Failure feedback; the HTTP response becomes {@code 400}. */
	public void sendError(Component message) {
		add(message.getString(), true);
	}

	/** Failure feedback that also marks the request as "no world loaded" ({@code 409}). */
	public void sendNotReady(Component message) {
		this.notReady = true;
		add(message.getString(), true);
	}

	private void add(String text, boolean error) {
		synchronized (this.messages) {
			if (this.messages.size() < MAX_MESSAGES) {
				this.messages.add(text);
			}
		}

		if (error) {
			this.failed = true;
		}

		CraftCmdMod.LOGGER.info("[craftcmd] {}", text);
	}

	/** Declares that this request is only over once the started job reports back. */
	public void expectAsync() {
		this.async = true;
	}

	/** @return true when {@link #expectAsync()} was called, i.e. a job owns the completion */
	public boolean isAsync() {
		return this.async;
	}

	/** Marks the request as finished and wakes the waiting HTTP thread (idempotent). */
	public void complete() {
		this.done.countDown();
	}

	/**
	 * @param timeoutMs how long to wait
	 * @return true when the request finished, false on timeout
	 */
	public boolean await(long timeoutMs) throws InterruptedException {
		return this.done.await(timeoutMs, TimeUnit.MILLISECONDS);
	}

	public boolean failed() {
		return this.failed;
	}

	public boolean notReady() {
		return this.notReady;
	}

	/** Everything the command said, one message per line (always newline terminated). */
	public String text() {
		synchronized (this.messages) {
			if (this.messages.isEmpty()) {
				return "";
			}

			StringBuilder out = new StringBuilder(this.messages.size() * 48);

			for (String message : this.messages) {
				out.append(message).append('\n');
			}

			return out.toString();
		}
	}
}
