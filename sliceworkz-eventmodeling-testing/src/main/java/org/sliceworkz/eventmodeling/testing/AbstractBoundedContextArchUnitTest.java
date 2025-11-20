/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.testing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Abstract Base Test to be overriden by a concrete Test Class in a root package for the overall application.
 * Checks 

 * VSA - Vertical Slice Architecture:
 * 		- whether no feature packages (*.features._featureName_.*) depends on another one. 
 * 
 * 
 * 
 * Overriding is done with an empty class, in the right base package.
 * (see "CoursesArchUnitTest" in the "courses" example for reference) 
 * 
 * package com.mycompany.mycomponent;
 * 
 * public class MyDomainArchUnitTest extends AbstractBoundedContextArchUnitTest {
 * 
 * }
 * 
 */
public abstract class AbstractBoundedContextArchUnitTest {

	private JavaClasses CLASSES;
	
	public AbstractBoundedContextArchUnitTest ( ) {
		 this.CLASSES = new ClassFileImporter().importPackages(packageName());
	}
	
	private String packageName ( ) {
		return this.getClass().getPackageName();
	}

	@Test
	void featurePackagesShouldNotDependOnEachOther() {
		ArchRule rule = noClasses().that().resideInAPackage("..features..*").should(dependOnClassesFromOtherFeatures())
				.because("Features should be independent and not depend on classes in other feature packages");

		rule.check(CLASSES);
	}

	private static ArchCondition<JavaClass> dependOnClassesFromOtherFeatures() {
		return new ArchCondition<JavaClass>("depend on classes from other features") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				System.err.println("checking " + javaClass);

				if (javaClass.toString().contains("ActivateClockCom")) {
					System.err.println("w");
				}

				Pattern featurePattern = Pattern.compile(".*\\.features\\.([^.]+)");
				java.util.regex.Matcher currentFeatureMatcher = featurePattern.matcher(javaClass.getPackageName());

				if (!currentFeatureMatcher.find()) {
					return; // Not in a feature package
				}

				String currentFeature = currentFeatureMatcher.group(1);

				javaClass.getDirectDependenciesFromSelf().stream().filter(dependency -> {
					String targetPackage = dependency.getTargetClass().getPackageName();
					java.util.regex.Matcher targetMatcher = featurePattern.matcher(targetPackage);
					if (!targetMatcher.find()) {
						return false; // Target not in features package
					}
					String targetFeature = targetMatcher.group(1);
					if (currentFeature.equals(targetFeature)) {
						return false; // Same feature - allowed
					}

					return true;

				}).forEach(dependency -> {
					String message = "Class %s depends on %s from different feature".formatted(javaClass.getName(),
							dependency.getTargetClass().getName());
					events.add(SimpleConditionEvent.satisfied(dependency, message));
				});
			}
		};
	}
}
