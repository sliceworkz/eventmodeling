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
package org.sliceworkz.eventmodeling.module.dcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.rules.EnforcementLevel;
import org.sliceworkz.eventmodeling.rules.Evaluation;
import org.sliceworkz.eventmodeling.rules.RuleJudgement;
import org.sliceworkz.eventmodeling.rules.RuleJudgement.Verdict;
import org.sliceworkz.eventmodeling.rules.RuleViolation;
import org.sliceworkz.eventmodeling.rules.RuleViolation.Disposition;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the two JSON shapes the business rules put on a wire: the {@link RuleViolation} stored inside event
 * payloads — its components and nothing else, so it reads back under {@code FAIL_ON_UNKNOWN_PROPERTIES} as
 * the eventstore's serde reads every payload — and the {@link Evaluation} a check endpoint answers, which
 * carries the two derived facts a front end lays its widgets out by.
 */
public class BusinessRuleJsonTest {

	private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

	@Test
	void aRecordedViolationIsItsComponentsAndReadsBack ( ) {
		RuleViolation violation = new RuleViolation("no-overdraft", EnforcementLevel.PRE_AUTHORIZED_OVERRIDE, Disposition.OVERRIDDEN, "negative", null);

		JsonNode json = mapper.readTree(mapper.writeValueAsString(violation));
		assertEquals(List.of("rule", "enforcementLevel", "disposition", "message", "explanation"), fieldNames(json));
		assertEquals(violation, mapper.readValue(mapper.writeValueAsString(violation), RuleViolation.class));
	}

	@Test
	void anEvaluationTellsAFrontEndWhatToRender ( ) {
		Evaluation evaluation = Evaluation.of(List.of(
				new RuleJudgement("origin-of-funds-explained", "Explain large deposits.", EnforcementLevel.OVERRIDE_WITH_EXPLANATION,
						"A deposit of 12000 is above 10000", Verdict.OVERRIDE_REQUIRED, null, null)));

		JsonNode json = mapper.readTree(mapper.writeValueAsString(evaluation));
		assertEquals("NEEDS_OVERRIDE", json.get("outcome").asString());
		JsonNode judgement = json.get("judgements").get(0);
		assertEquals("origin-of-funds-explained", judgement.get("rule").asString());
		assertEquals("OVERRIDE_REQUIRED", judgement.get("verdict").asString());
		assertTrue(judgement.get("overridable").asBoolean());
		assertTrue(judgement.get("explanationRequired").asBoolean());
		assertFalse(json.has("stopping"), "derived lists are not properties: " + json);

		assertEquals(evaluation, mapper.readValue(mapper.writeValueAsString(evaluation), Evaluation.class));
	}

	private static List<String> fieldNames ( JsonNode json ) {
		return json.propertyNames().stream().toList();
	}

}
