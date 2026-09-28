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
package org.sliceworkz.eventmodeling.rules;

import java.io.Serial;
import java.util.stream.Collectors;

import org.sliceworkz.eventmodeling.commands.BusinessException;

/**
 * An execution rejected because business rules were violated and not (or not sufficiently) overridden.
 * <p>
 * It is a {@link BusinessException}, deliberately: a rejection on a business rule is an outcome of the
 * command, not a bug, and everything that already tells the two apart keeps doing so — a {@code catch
 * ( BusinessException )} in application code, the kernel reporting it as a {@code CommandRejected} rather
 * than a {@code CommandFailed}, {@code executeWithRetry} leaving it alone, and {@code businessError(...)}
 * in the test harness. What it adds is the structure: {@link #evaluation()} is the same {@link Evaluation}
 * that evaluating the command would have answered, so the handler of a rejected submit can return exactly
 * what a check returns (an HTTP {@code 422} with the judgements as its body), and a front end has one shape
 * to render.
 * <p>
 * It is thrown by the kernel after the command ran, never by the command: a command {@linkplain
 * org.sliceworkz.eventmodeling.commands.CommandContext#check checks} its rules, and the kernel judges them.
 */
public class RuleViolationException extends BusinessException {

	@Serial
	private static final long serialVersionUID = 1L;

	private final Evaluation evaluation;

	/**
	 * @param evaluation the evaluation of the rejected execution, its outcome {@link Evaluation.Outcome#BLOCKED}
	 *                   or {@link Evaluation.Outcome#NEEDS_OVERRIDE}
	 */
	public RuleViolationException ( Evaluation evaluation ) {
		super(messageOf(evaluation));
		this.evaluation = evaluation;
	}

	/**
	 * @return the evaluation of the rejected execution: every violated rule and its judgement
	 */
	public Evaluation evaluation ( ) {
		return evaluation;
	}

	private static String messageOf ( Evaluation evaluation ) {
		return "Business rules violated: " + evaluation.stopping().stream()
				.map(RuleJudgement::toString)
				.collect(Collectors.joining("; "));
	}

}
