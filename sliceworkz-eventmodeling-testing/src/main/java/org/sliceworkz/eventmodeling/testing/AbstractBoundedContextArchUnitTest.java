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

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventmodeling.slices.FeatureSliceConfiguration;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Abstract Base Test to be overridden by a concrete Test Class in a root package for the overall application.
 * Checks
 *
 * VSA - Vertical Slice Architecture:
 * 		- whether no feature packages depend on another one.
 *
 * A feature package is any package that contains a class implementing
 * {@link FeatureSliceConfiguration} (directly or via extending). Classes in
 * subpackages of a feature package are considered part of the same feature.
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
		Set<String> featurePackages = findFeaturePackages(CLASSES);

		if (featurePackages.isEmpty()) {
			return;
		}

		ArchRule rule = noClasses()
				.that(resideInFeaturePackages(featurePackages))
				.should(dependOnClassesFromOtherFeaturePackages(featurePackages))
				.because("Features should be independent and not depend on classes in other feature packages");

		rule.check(CLASSES);
	}

	private static Set<String> findFeaturePackages(JavaClasses classes) {
		Set<String> featurePackages = new HashSet<>();
		for (JavaClass javaClass : classes) {
			if (!javaClass.isInterface() && javaClass.isAssignableTo(FeatureSliceConfiguration.class)) {
				featurePackages.add(javaClass.getPackageName());
			}
		}
		return featurePackages;
	}

	private static String findOwningFeaturePackage(String packageName, Set<String> featurePackages) {
		String best = null;
		for (String featurePackage : featurePackages) {
			if (packageName.equals(featurePackage) || packageName.startsWith(featurePackage + ".")) {
				if (best == null || featurePackage.length() > best.length()) {
					best = featurePackage;
				}
			}
		}
		return best;
	}

	private static DescribedPredicate<JavaClass> resideInFeaturePackages(Set<String> featurePackages) {
		return new DescribedPredicate<>("reside in a feature package") {
			@Override
			public boolean test(JavaClass javaClass) {
				return findOwningFeaturePackage(javaClass.getPackageName(), featurePackages) != null;
			}
		};
	}

	private static ArchCondition<JavaClass> dependOnClassesFromOtherFeaturePackages(Set<String> featurePackages) {
		return new ArchCondition<>("depend on classes from other feature packages") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				String currentFeature = findOwningFeaturePackage(javaClass.getPackageName(), featurePackages);
				if (currentFeature == null) {
					return;
				}

				javaClass.getDirectDependenciesFromSelf().stream()
						.filter(dependency -> {
							String targetFeature = findOwningFeaturePackage(
									dependency.getTargetClass().getPackageName(), featurePackages);
							if (targetFeature == null) {
								return false;
							}
							return !currentFeature.equals(targetFeature);
						})
						.forEach(dependency -> {
							String message = "Class %s depends on %s from different feature package".formatted(
									javaClass.getName(),
									dependency.getTargetClass().getName());
							events.add(SimpleConditionEvent.satisfied(dependency, message));
						});
			}
		};
	}
}
