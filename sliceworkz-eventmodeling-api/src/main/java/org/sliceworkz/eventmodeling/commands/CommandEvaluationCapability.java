/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventmodeling.commands;

import org.sliceworkz.eventmodeling.events.Tracing;
import org.sliceworkz.eventmodeling.rules.Evaluation;

/**
 * Evaluating a command: running it as an execution would — decision models projected, its
 * {@code BusinessException}s thrown, its {@link org.sliceworkz.eventmodeling.rules.BusinessRule business
 * rules} checked and judged — and stopping short of the append.
 * <p>
 * What it is for is showing a user, before they submit, what will work and what will not: which rules a
 * request violates, which of them this user may override (and whether they have to explain why), and which
 * are only advice. Bound to HTTP, it is the {@code QUERY} method on the same resource a {@code POST}
 * executes the command on — safe, idempotent, carrying the same body:
 * <pre>{@code
 * routes.post ("/api/withdrawals", ctx -> banking.execute(toCommand(ctx), tracingOf(ctx)));
 * routes.query("/api/withdrawals", ctx -> ctx.json(banking.evaluate(toCommand(ctx), tracingOf(ctx))));
 * }</pre>
 * <h2>What an evaluation does not do</h2>
 * <ul>
 * <li>It appends nothing, spends no idempotency key and emits no {@code CommandExecuted}: a front end may
 *     evaluate on every change of a form, and the monitoring record should not fill up with previews. It is
 *     reported to the observer as a {@code CommandEvaluation}.</li>
 * <li>It promises nothing about a later execution. The facts may change in between, and the execution judges
 *     every rule again on the facts as they are then — which is also why an override is acknowledged per rule
 *     and re-judged, rather than handed out as a token.</li>
 * </ul>
 * The command is run for real up to the append, so it must be free of side effects in {@code execute} —
 * which every command already is, since a command that conflicts is executed again.
 * <p>
 * Filed on {@link org.sliceworkz.eventmodeling.boundedcontext.ApplicationCapabilities}, beside executing:
 * the code that submits a command is the code that previews it. Automations and translators have no user to
 * show a preview to, and their contexts do not carry it.
 *
 * @param <DOMAIN_EVENT_TYPE> the bounded context's domain event type
 */
public interface CommandEvaluationCapability<DOMAIN_EVENT_TYPE> {

	/**
	 * Evaluates a command for an anonymous caller. A rule that only an identified actor may override is
	 * therefore judged as blocking; pass a {@link Tracing} naming the actor to evaluate it for someone.
	 *
	 * @param command the command
	 * @return what executing it now would do
	 */
	Evaluation evaluate ( Command<DOMAIN_EVENT_TYPE> command );

	/**
	 * Evaluates a command for the actor the tracing names.
	 *
	 * @param command the command
	 * @param tracing the caller's tracing, as it would be passed to {@code execute}
	 * @return what executing it now would do
	 */
	Evaluation evaluate ( Command<DOMAIN_EVENT_TYPE> command, Tracing tracing );

	/**
	 * Evaluates a command with a result for an anonymous caller. The response it computes is discarded.
	 *
	 * @param command the command
	 * @return what executing it now would do
	 */
	Evaluation evaluate ( CommandWithResult<DOMAIN_EVENT_TYPE, ?> command );

	/**
	 * Evaluates a command with a result for the actor the tracing names. The response it computes is discarded.
	 *
	 * @param command the command
	 * @param tracing the caller's tracing, as it would be passed to {@code execute}
	 * @return what executing it now would do
	 */
	Evaluation evaluate ( CommandWithResult<DOMAIN_EVENT_TYPE, ?> command, Tracing tracing );

}
