/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components


import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemOperation
import org.jetbrains.kotlin.resolve.calls.inference.model.*
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintKind.LOWER
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintKind.UPPER
import org.jetbrains.kotlin.resolve.calls.model.KotlinCallDiagnostic
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.KotlinTypeRefiner
import org.jetbrains.kotlin.types.model.*
import org.jetbrains.kotlin.types.refinement.TypeRefinement
import org.jetbrains.kotlin.utils.SmartList
import kotlin.math.max

class ConstraintInjector(
    val constraintIncorporator: ConstraintIncorporator,
    val typeApproximator: AbstractTypeApproximator,
    val kotlinTypeRefiner: KotlinTypeRefiner
) {
    private val ALLOWED_DEPTH_DELTA_FOR_INCORPORATION = 1

    interface Context {
        val baseContext: TypeSystemInferenceExtensionContext

        val allTypeVariables: Map<TypeConstructorMarker, TypeVariableMarker>

        var maxTypeDepthFromInitialConstraints: Int
        val notFixedTypeVariables: MutableMap<TypeConstructorMarker, MutableVariableWithConstraints>
        val fixedTypeVariables: MutableMap<TypeConstructorMarker, KotlinTypeMarker>

        fun addInitialConstraint(initialConstraint: InitialConstraint)
        fun addError(error: KotlinCallDiagnostic)
    }

    fun addInitialSubtypeConstraint(c: Context, lowerType: KotlinTypeMarker, upperType: KotlinTypeMarker, position: ConstraintPosition) {
        val initialConstraint = InitialConstraint(lowerType, upperType, UPPER, position)
        c.addInitialConstraint(initialConstraint)
        updateAllowedTypeDepth(c, lowerType)
        updateAllowedTypeDepth(c, upperType)
        addSubTypeConstraintAndIncorporateIt(c, lowerType, upperType, IncorporationConstraintPosition(position, initialConstraint))
    }

    fun addInitialEqualityConstraint(c: Context, a: KotlinTypeMarker, b: KotlinTypeMarker, position: ConstraintPosition) {
        val initialConstraint = InitialConstraint(a, b, ConstraintKind.EQUALITY, position)
        c.addInitialConstraint(initialConstraint)
        updateAllowedTypeDepth(c, a)
        updateAllowedTypeDepth(c, b)
        addSubTypeConstraintAndIncorporateIt(c, a, b, IncorporationConstraintPosition(position, initialConstraint))
        addSubTypeConstraintAndIncorporateIt(c, b, a, IncorporationConstraintPosition(position, initialConstraint))
    }

    private fun addSubTypeConstraintAndIncorporateIt(
        c: Context,
        lowerType: KotlinTypeMarker,
        upperType: KotlinTypeMarker,
        incorporatePosition: IncorporationConstraintPosition
    ) {
        var possibleNewConstraints: MutableList<Pair<TypeVariableMarker, Constraint>>? = null

        val typeCheckerContext = TypeCheckerContext(c, incorporatePosition, lowerType, upperType) { typeVar, constraint ->
            if (possibleNewConstraints == null) {
                possibleNewConstraints = SmartList()
            }
            possibleNewConstraints!!.add(typeVar to constraint)
        }
        typeCheckerContext.runIsSubtypeOf(lowerType, upperType)

        while (possibleNewConstraints != null) {

            val constraintsToProcess = possibleNewConstraints
            possibleNewConstraints = null
            for ((typeVariable, constraint) in constraintsToProcess!!) {
                if (c.shouldWeSkipConstraint(typeVariable, constraint)) continue

                val constraints =
                    c.notFixedTypeVariables[typeVariable.freshTypeConstructor(c.baseContext)] ?: typeCheckerContext.fixedTypeVariable(typeVariable)

                // it is important, that we add constraint here(not inside TypeCheckerContext), because inside incorporation we read constraints
                constraints.addConstraint(constraint)?.let {
                    if (!constraint.isNullabilityConstraint) {
                        constraintIncorporator.incorporate(typeCheckerContext, typeVariable, it)
                    }
                }
            }

            val contextOps = c as? ConstraintSystemOperation
            if (possibleNewConstraints == null ||
                (contextOps != null && c.notFixedTypeVariables.all { typeVariable ->
                    typeVariable.value.constraints.any { constraint ->
                        constraint.kind == ConstraintKind.EQUALITY && contextOps.isProperType(constraint.type)
                    }
                })
            ) {
                break
            }
        }
    }

    private fun updateAllowedTypeDepth(c: Context, initialType: KotlinTypeMarker) = with(c.baseContext) {
        c.maxTypeDepthFromInitialConstraints = max(c.maxTypeDepthFromInitialConstraints, initialType.typeDepth())
    }

    private fun Context.shouldWeSkipConstraint(typeVariable: TypeVariableMarker, constraint: Constraint): Boolean {
        with(baseContext) {
            assert(constraint.kind != ConstraintKind.EQUALITY)

            val constraintType = constraint.type
            if (!isAllowedType(constraintType)) return true

            if (constraintType.typeConstructor() == typeVariable.freshTypeConstructor()) {
                if (constraintType.lowerBoundIfFlexible().isMarkedNullable() && constraint.kind == LOWER) return false // T? <: T

                return true // T <: T(?!)
            }

            if (constraint.position.from is DeclaredUpperBoundConstraintPosition &&
                constraint.kind == UPPER && constraintType.isNullableAny()
            ) {
                return true // T <: Any?
            }

            return false
        }
    }

    private fun Context.isAllowedType(type: KotlinTypeMarker) = with(baseContext) {
        type.typeDepth() <= maxTypeDepthFromInitialConstraints + ALLOWED_DEPTH_DELTA_FOR_INCORPORATION
    }

    private inner class TypeCheckerContext(
        val injectorContext: Context,
        val position: IncorporationConstraintPosition,
        val baseLowerType: KotlinTypeMarker,
        val baseUpperType: KotlinTypeMarker,
        val addPossibleNewConstraints: (TypeVariableMarker, Constraint) -> Unit
    ) : AbstractTypeCheckerContextForConstraintSystem(injectorContext.baseContext), ConstraintIncorporator.IncorporatorContext {
        val baseTypeCheckerContext: AbstractTypeCheckerContext = baseContext.newBaseTypeCheckerContext(isErrorTypeEqualsToAnything, isStubTypeEqualsToAnything)

        override fun substitutionSupertypePolicy(type: SimpleTypeMarker): SupertypesPolicy {
            return baseTypeCheckerContext.substitutionSupertypePolicy(type)
        }

        override fun areEqualTypeConstructors(a: TypeConstructorMarker, b: TypeConstructorMarker): Boolean {
            return baseTypeCheckerContext.areEqualTypeConstructors(a, b)
        }

        override fun prepareType(type: KotlinTypeMarker): KotlinTypeMarker {
            return baseTypeCheckerContext.prepareType(type)
        }

        @OptIn(TypeRefinement::class)
        override fun refineType(type: KotlinTypeMarker): KotlinTypeMarker {
            return if (type is KotlinType) {
                kotlinTypeRefiner.refineType(type)
            } else {
                type
            }
        }

        fun runIsSubtypeOf(
            lowerType: KotlinTypeMarker,
            upperType: KotlinTypeMarker,
            shouldTryUseDifferentFlexibilityForUpperType: Boolean = false
        ) {
            fun isSubtypeOf(upperType: KotlinTypeMarker) =
                AbstractTypeChecker.isSubtypeOf(
                    this,
                    lowerType,
                    upperType
                )

            if (!isSubtypeOf(upperType)) {
                // todo improve error reporting -- add information about base types
                if (shouldTryUseDifferentFlexibilityForUpperType && upperType is SimpleType) {
                    /*
                     * Please don't reuse this logic.
                     * It's necessary to solve constraint systems when flexibility isn't propagated through a type variable.
                     * It's OK in the old inference because it uses already substituted types, that are with the correct flexibility.
                     */
                    val flexibleUpperType = with(baseContext) { createFlexibleType(upperType, upperType.withNullability(true)) }
                    if (!isSubtypeOf(flexibleUpperType)) {
                        injectorContext.addError(NewConstraintError(lowerType, flexibleUpperType, position))
                    }
                } else {
                    injectorContext.addError(NewConstraintError(lowerType, upperType, position))
                }
            }
        }

        // from AbstractTypeCheckerContextForConstraintSystem
        override fun isMyTypeVariable(type: SimpleTypeMarker): Boolean = with(baseContext) {
            type.mayBeTypeVariable() && injectorContext.allTypeVariables.containsKey(type.typeConstructor())
        }

        override fun addUpperConstraint(typeVariable: TypeConstructorMarker, superType: KotlinTypeMarker) =
            addConstraint(typeVariable, superType, UPPER)

        override fun addLowerConstraint(typeVariable: TypeConstructorMarker, subType: KotlinTypeMarker) =
            addConstraint(typeVariable, subType, LOWER)

        private fun isCapturedTypeFromSubtyping(type: KotlinTypeMarker) =
            when ((type as? CapturedTypeMarker)?.captureStatus(baseContext)) {
                null, CaptureStatus.FROM_EXPRESSION -> false
                CaptureStatus.FOR_SUBTYPING -> true
                CaptureStatus.FOR_INCORPORATION ->
                    error("Captured type for incorporation shouldn't escape from incorporation: $type\n" + renderBaseConstraint())
            }

        private fun addConstraint(typeVariableConstructor: TypeConstructorMarker, type: KotlinTypeMarker, kind: ConstraintKind) {
            val typeVariable = injectorContext.allTypeVariables[typeVariableConstructor]
                ?: error("Should by type variableConstructor: $typeVariableConstructor. ${injectorContext.allTypeVariables.values}")

            addNewIncorporatedConstraint(typeVariable, type, ConstraintContext(kind, emptySet(), isNullabilityConstraint = false))
        }

        // from ConstraintIncorporator.Context
        override fun addNewIncorporatedConstraint(
            lowerType: KotlinTypeMarker,
            upperType: KotlinTypeMarker,
            shouldTryUseDifferentFlexibilityForUpperType: Boolean
        ) {
            if (lowerType === upperType) return
            if (injectorContext.isAllowedType(lowerType) && injectorContext.isAllowedType(upperType)) {
                runIsSubtypeOf(lowerType, upperType, shouldTryUseDifferentFlexibilityForUpperType)
            }
        }

        override fun addNewIncorporatedConstraint(
            typeVariable: TypeVariableMarker,
            type: KotlinTypeMarker,
            constraintContext: ConstraintContext
        ) {
            with(baseContext) {
                val (kind, derivedFrom, inputTypePosition, isNullabilityConstraint) = constraintContext

                var targetType = type
                if (targetType.isUninferredParameter()) {
                    // there already should be an error, so there is no point in reporting one more
                    return
                }

                if (targetType.isError()) {
                    injectorContext.addError(ConstrainingTypeIsError(typeVariable, targetType, position))
                    return
                }

                if (type.contains(::isCapturedTypeFromSubtyping)) {
                    // TypeVariable <: type -> if TypeVariable <: subType => TypeVariable <: type
                    if (kind == UPPER) {
                        val subType =
                            typeApproximator.approximateToSubType(type, TypeApproximatorConfiguration.SubtypeCapturedTypesApproximation)
                        if (subType != null) {
                            targetType = subType
                        }
                    }

                    if (kind == LOWER) {
                        val superType =
                            typeApproximator.approximateToSuperType(type, TypeApproximatorConfiguration.SubtypeCapturedTypesApproximation)
                        if (superType != null) { // todo rethink error reporting for Any cases
                            targetType = superType
                        }
                    }

                    if (targetType === type) {
                        injectorContext.addError(CapturedTypeFromSubtyping(typeVariable, type, position))
                        return
                    }
                }

                val newConstraint = Constraint(
                    kind, targetType, position,
                    derivedFrom = derivedFrom,
                    isNullabilityConstraint = isNullabilityConstraint,
                    inputTypePositionBeforeIncorporation = inputTypePosition
                )
                addPossibleNewConstraints(typeVariable, newConstraint)
            }
        }

        override val allTypeVariablesWithConstraints: Collection<VariableWithConstraints>
            get() = injectorContext.notFixedTypeVariables.values

        override fun getTypeVariable(typeConstructor: TypeConstructorMarker): TypeVariableMarker? {
            val typeVariable = injectorContext.allTypeVariables[typeConstructor]
            if (typeVariable != null && !injectorContext.notFixedTypeVariables.containsKey(typeConstructor)) {
                fixedTypeVariable(typeVariable)
            }
            return typeVariable
        }

        override fun getConstraintsForVariable(typeVariable: TypeVariableMarker) =
            injectorContext.notFixedTypeVariables[typeVariable.freshTypeConstructor(baseContext)]?.constraints
                ?: fixedTypeVariable(typeVariable)

        fun fixedTypeVariable(variable: TypeVariableMarker): Nothing {
            error(
                "Type variable $variable should not be fixed!\n" +
                        renderBaseConstraint()
            )
        }

        private fun renderBaseConstraint() = "Base constraint: $baseLowerType <: $baseUpperType from position: $position"
    }
}

data class ConstraintContext(
    val kind: ConstraintKind,
    val derivedFrom: Set<TypeVariableMarker>,
    val inputTypePositionBeforeIncorporation: OnlyInputTypeConstraintPosition? = null,
    val isNullabilityConstraint: Boolean
)
