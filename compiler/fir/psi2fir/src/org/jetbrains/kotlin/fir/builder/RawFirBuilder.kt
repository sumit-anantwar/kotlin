/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.builder

import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirFunctionTarget
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.*
import org.jetbrains.kotlin.fir.references.FirErrorMemberReference
import org.jetbrains.kotlin.fir.references.FirSimpleMemberReference
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.FirType
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.fir.types.impl.*
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.lexer.KtTokens.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.hasActualModifier
import org.jetbrains.kotlin.psi.psiUtil.hasExpectModifier
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.util.OperatorNameConventions

class RawFirBuilder(val session: FirSession, val stubMode: Boolean) {

    private val implicitUnitType = FirImplicitUnitType(session, null)

    private val implicitAnyType = FirImplicitAnyType(session, null)

    private val implicitEnumType = FirImplicitEnumType(session, null)

    fun buildFirFile(file: KtFile): FirFile {
        return file.accept(Visitor(), Unit) as FirFile
    }

    private val KtModifierListOwner.visibility: Visibility
        get() = with(modifierList) {
            when {
                this == null -> Visibilities.UNKNOWN
                hasModifier(PRIVATE_KEYWORD) -> Visibilities.PRIVATE
                hasModifier(PUBLIC_KEYWORD) -> Visibilities.PUBLIC
                hasModifier(PROTECTED_KEYWORD) -> Visibilities.PROTECTED
                else -> if (hasModifier(INTERNAL_KEYWORD)) Visibilities.INTERNAL else Visibilities.UNKNOWN
            }
        }

    private val KtDeclaration.modality: Modality?
        get() = with(modifierList) {
            when {
                this == null -> null
                hasModifier(FINAL_KEYWORD) -> Modality.FINAL
                hasModifier(SEALED_KEYWORD) -> Modality.SEALED
                hasModifier(ABSTRACT_KEYWORD) -> Modality.ABSTRACT
                else -> if (hasModifier(OPEN_KEYWORD)) Modality.OPEN else null
            }
        }

    private inner class Visitor : KtVisitor<FirElement, Unit>() {
        private inline fun <reified R : FirElement> KtElement?.convertSafe(): R? =
            this?.accept(this@Visitor, Unit) as? R

        private inline fun <reified R : FirElement> KtElement.convert(): R =
            this.accept(this@Visitor, Unit) as R

        private fun KtTypeReference?.toFirOrImplicitType(): FirType =
            convertSafe() ?: FirImplicitTypeImpl(session, this)

        private fun KtTypeReference?.toFirOrUnitType(): FirType =
            convertSafe() ?: implicitUnitType

        private fun KtTypeReference?.toFirOrErrorType(): FirType =
            convertSafe() ?: FirErrorTypeImpl(session, this, if (this == null) "Incomplete code" else "Conversion failed")

        // Here and below we accept lambda as receiver
        // to prevent expression calculation in stub mode
        private fun (() -> KtExpression?).toFirExpression(errorReason: String): FirExpression =
            if (stubMode) FirExpressionStub(session, null)
            else with(this()) {
                convertSafe<FirExpression>() ?: FirErrorExpressionImpl(session, this, errorReason)
            }

        private fun (() -> KtExpression).toFirExpression(): FirExpression =
            if (stubMode) FirExpressionStub(session, null)
            else this().convert<FirExpression>()

        private fun KtExpression.toFirExpression(): FirExpression =
            if (stubMode) FirExpressionStub(session, null) else convert<FirExpression>()

        private fun KtExpression?.toFirExpression(errorReason: String): FirExpression =
            if (stubMode) FirExpressionStub(session, null)
            else convertSafe<FirExpression>() ?: FirErrorExpressionImpl(session, this, errorReason)

        private fun KtExpression.toFirStatement(errorReason: String): FirStatement {
            return convertSafe<FirStatement>() ?: FirErrorExpressionImpl(session, this, errorReason)
        }

        private fun KtExpression?.toFirBlock(): FirBlock =
            when (this) {
                is KtBlockExpression ->
                    accept(this@Visitor, Unit) as FirBlock
                null ->
                    FirEmptyExpressionBlock(session)
                else ->
                    FirSingleExpressionBlock(
                        session,
                        toFirExpression()
                    )
            }

        private fun FirExpression.toReturn(labelName: String? = null): FirReturnStatement {
            return FirReturnStatementImpl(
                session,
                this.psi,
                this
            ).apply {
                target = FirFunctionTarget(labelName)
                if (labelName == null) {
                    target.bind(firFunctions.last())
                } else {
                    // TODO
                }
            }
        }

        private fun KtDeclarationWithBody.buildFirBody(): FirBlock? =
            when {
                !hasBody() ->
                    null
                hasBlockBody() -> if (!stubMode) {
                    bodyBlockExpression?.accept(this@Visitor, Unit) as? FirBlock
                } else {
                    FirSingleExpressionBlock(
                        session,
                        FirExpressionStub(session, this).toReturn()
                    )
                }
                else -> {
                    val result = { bodyExpression }.toFirExpression("Function has no body (but should)")
                    FirSingleExpressionBlock(
                        session,
                        result.toReturn()
                    )
                }
            }

        private fun String.parseCharacter(): Char? {
            // Strip the quotes
            if (length < 2 || this[0] != '\'' || this[length - 1] != '\'') {
                return null
            }
            val text = substring(1, length - 1) // now there're no quotes

            if (text.isEmpty()) {
                return null
            }

            return if (text[0] != '\\') {
                // No escape
                if (text.length == 1) {
                    text[0]
                } else {
                    null
                }
            } else {
                escapedStringToCharacter(text)
            }
        }

        private fun escapedStringToCharacter(text: String): Char? {
            assert(text.isNotEmpty() && text[0] == '\\') {
                "Only escaped sequences must be passed to this routine: $text"
            }

            // Escape
            val escape = text.substring(1) // strip the slash
            when (escape.length) {
                0 -> {
                    // bare slash
                    return null
                }
                1 -> {
                    // one-char escape
                    return translateEscape(escape[0]) ?: return null
                }
                5 -> {
                    // unicode escape
                    if (escape[0] == 'u') {
                        try {
                            val intValue = Integer.valueOf(escape.substring(1), 16)
                            return intValue.toInt().toChar()
                        } catch (e: NumberFormatException) {
                            // Will be reported below
                        }
                    }
                }
            }
            return null
        }

        private fun translateEscape(c: Char): Char? =
            when (c) {
                't' -> '\t'
                'b' -> '\b'
                'n' -> '\n'
                'r' -> '\r'
                '\'' -> '\''
                '\"' -> '\"'
                '\\' -> '\\'
                '$' -> '$'
                else -> null
            }

        private fun ValueArgument?.toFirExpression(): FirExpression {
            this ?: return FirErrorExpressionImpl(session, this as? KtElement, "No argument given")
            val expression = this.getArgumentExpression()
            return when (expression) {
                is KtConstantExpression, is KtStringTemplateExpression -> {
                    expression.accept(this@Visitor, Unit) as FirExpression
                }

                else -> {
                    { expression }.toFirExpression("Argument is absent")
                }
            }
        }

        private fun KtPropertyAccessor?.toFirPropertyAccessor(
            property: KtProperty,
            propertyType: FirType,
            isGetter: Boolean
        ): FirPropertyAccessor {
            if (this == null) {
                return if (isGetter) {
                    FirDefaultPropertyGetter(session, property, propertyType, property.visibility)
                } else {
                    FirDefaultPropertySetter(session, property, propertyType, property.visibility)
                }
            }
            val firAccessor = FirPropertyAccessorImpl(
                session,
                this,
                isGetter,
                visibility,
                if (isGetter) {
                    returnTypeReference?.convertSafe() ?: propertyType
                } else {
                    returnTypeReference.toFirOrUnitType()
                }
            )
            firFunctions += firAccessor
            extractAnnotationsTo(firAccessor)
            extractValueParametersTo(firAccessor, propertyType)
            if (!isGetter && firAccessor.valueParameters.isEmpty()) {
                firAccessor.valueParameters += FirDefaultSetterValueParameter(session, this, propertyType)
            }
            firAccessor.body = this.buildFirBody()
            firFunctions.removeLast()
            return firAccessor
        }

        private fun KtParameter.toFirValueParameter(defaultType: FirType? = null): FirValueParameter {
            val firValueParameter = FirValueParameterImpl(
                session,
                this,
                nameAsSafeName,
                when {
                    typeReference != null -> typeReference.toFirOrErrorType()
                    defaultType != null -> defaultType
                    else -> null.toFirOrErrorType()
                },
                if (hasDefaultValue()) {
                    { defaultValue }.toFirExpression("Should have default value")
                } else null,
                isCrossinline = hasModifier(KtTokens.CROSSINLINE_KEYWORD),
                isNoinline = hasModifier(KtTokens.NOINLINE_KEYWORD),
                isVararg = isVarArg
            )
            extractAnnotationsTo(firValueParameter)
            return firValueParameter
        }

        private fun KtParameter.toFirProperty(): FirProperty {
            require(hasValOrVar())
            val type = typeReference.toFirOrErrorType()
            val firProperty = FirMemberPropertyImpl(
                session,
                this,
                nameAsSafeName,
                visibility,
                modality,
                hasExpectModifier(),
                hasActualModifier(),
                isOverride = hasModifier(KtTokens.OVERRIDE_KEYWORD),
                isConst = false,
                isLateInit = false,
                receiverType = null,
                returnType = type,
                isVar = isMutable,
                initializer = null,
                getter = FirDefaultPropertyGetter(session, this, type, visibility),
                setter = FirDefaultPropertySetter(session, this, type, visibility),
                delegate = null
            )
            extractAnnotationsTo(firProperty)
            return firProperty
        }

        private fun KtModifierListOwner.extractAnnotationsTo(container: FirAbstractAnnotatedDeclaration) {
            for (annotationEntry in annotationEntries) {
                container.annotations += annotationEntry.convert<FirAnnotationCall>()
            }
        }

        private fun KtTypeParameterListOwner.extractTypeParametersTo(container: FirAbstractMemberDeclaration) {
            for (typeParameter in typeParameters) {
                container.typeParameters += typeParameter.convert<FirTypeParameter>()
            }
        }

        private fun KtDeclarationWithBody.extractValueParametersTo(
            container: FirFunction,
            defaultType: FirType? = null
        ) {
            for (valueParameter in valueParameters) {
                (container.valueParameters as MutableList<FirValueParameter>) += valueParameter.toFirValueParameter(defaultType)
            }
        }

        private fun KtCallElement.extractArgumentsTo(container: FirAbstractCall) {
            for (argument in this.valueArguments) {
                container.arguments += argument.toFirExpression()
            }
        }

        private fun KtClassOrObject.extractSuperTypeListEntriesTo(
            container: FirClassImpl, delegatedSelfType: FirType
        ): FirType? {
            var superTypeCallEntry: KtSuperTypeCallEntry? = null
            var delegatedSuperType: FirType? = null
            for (superTypeListEntry in superTypeListEntries) {
                when (superTypeListEntry) {
                    is KtSuperTypeEntry -> {
                        container.superTypes += superTypeListEntry.typeReference.toFirOrErrorType()
                    }
                    is KtSuperTypeCallEntry -> {
                        delegatedSuperType = superTypeListEntry.calleeExpression.typeReference.toFirOrErrorType()
                        container.superTypes += delegatedSuperType
                        superTypeCallEntry = superTypeListEntry
                    }
                    is KtDelegatedSuperTypeEntry -> {
                        val type = superTypeListEntry.typeReference.toFirOrErrorType()
                        container.superTypes += FirDelegatedTypeImpl(
                            type,
                            FirExpressionStub(session, superTypeListEntry)
                        )
                    }
                }
            }
            if (this is KtClass && this.isInterface()) return delegatedSuperType

            fun isEnum() = this is KtClass && this.isEnum()
            // TODO: in case we have no primary constructor,
            // it may be not possible to determine delegated super type right here
            delegatedSuperType = delegatedSuperType ?: (if (isEnum()) implicitEnumType else implicitAnyType)
            if (!this.hasPrimaryConstructor()) return delegatedSuperType

            val firPrimaryConstructor = primaryConstructor.toFirConstructor(
                superTypeCallEntry,
                delegatedSuperType,
                delegatedSelfType,
                owner = this
            )
            container.declarations += firPrimaryConstructor
            return delegatedSuperType
        }

        private fun KtPrimaryConstructor?.toFirConstructor(
            superTypeCallEntry: KtSuperTypeCallEntry?,
            delegatedSuperType: FirType,
            delegatedSelfType: FirType,
            owner: KtClassOrObject
        ): FirConstructor {
            val constructorCallee = superTypeCallEntry?.calleeExpression
            val firDelegatedCall = FirDelegatedConstructorCallImpl(
                session,
                constructorCallee ?: (this ?: owner),
                delegatedSuperType,
                isThis = false
            ).apply {
                // TODO: arguments are not needed for light classes, but will be needed later
                //superTypeCallEntry.extractArgumentsTo(this)
            }
            val firConstructor = FirPrimaryConstructorImpl(
                session,
                this ?: owner,
                this?.visibility ?: Visibilities.UNKNOWN,
                this?.hasExpectModifier() ?: false,
                this?.hasActualModifier() ?: false,
                delegatedSelfType,
                firDelegatedCall
            )
            this?.extractAnnotationsTo(firConstructor)
            this?.extractValueParametersTo(firConstructor)
            return firConstructor
        }

        lateinit var packageFqName: FqName

        override fun visitKtFile(file: KtFile, data: Unit): FirElement {
            packageFqName = file.packageFqName
            val firFile = FirFileImpl(session, file, file.name, packageFqName)
            for (annotationEntry in file.annotationEntries) {
                firFile.annotations += annotationEntry.convert<FirAnnotationCall>()
            }
            for (importDirective in file.importDirectives) {
                firFile.imports += FirImportImpl(
                    session,
                    importDirective,
                    importDirective.importedFqName,
                    importDirective.isAllUnder,
                    importDirective.aliasName?.let { Name.identifier(it) }
                )
            }
            for (declaration in file.declarations) {
                firFile.declarations += declaration.convert<FirDeclaration>()
            }
            return firFile
        }

        private fun KtClassOrObject.toDelegatedSelfType(): FirType =
            FirUserTypeImpl(session, this, isNullable = false).apply {
                qualifier.add(FirQualifierPartImpl(nameAsSafeName))
            }

        override fun visitEnumEntry(enumEntry: KtEnumEntry, data: Unit): FirElement {
            return withChildClassName(enumEntry.nameAsSafeName) {
                val firEnumEntry = FirEnumEntryImpl(
                    session,
                    enumEntry,
                    FirClassSymbol(currentClassId),
                    enumEntry.nameAsSafeName
                )
                enumEntry.extractAnnotationsTo(firEnumEntry)
                val delegatedSelfType = enumEntry.toDelegatedSelfType()
                val delegatedSuperType = enumEntry.extractSuperTypeListEntriesTo(firEnumEntry, delegatedSelfType)
                for (declaration in enumEntry.declarations) {
                    firEnumEntry.declarations += when (declaration) {
                        is KtSecondaryConstructor -> declaration.toFirConstructor(
                            delegatedSuperType,
                            delegatedSelfType,
                            hasPrimaryConstructor = true
                        )
                        else -> declaration.convert<FirDeclaration>()
                    }
                }
                firEnumEntry
            }
        }

        inline fun <T> withChildClassName(name: Name, l: () -> T): T {
            className = className.child(name)
            val t = l()
            className = className.parent()
            return t
        }

        val currentClassId get() = ClassId(packageFqName, className, false)

        var className: FqName = FqName.ROOT

        override fun visitClassOrObject(classOrObject: KtClassOrObject, data: Unit): FirElement {
            return withChildClassName(classOrObject.nameAsSafeName) {

                val classKind = when (classOrObject) {
                    is KtObjectDeclaration -> ClassKind.OBJECT
                    is KtClass -> when {
                        classOrObject.isInterface() -> ClassKind.INTERFACE
                        classOrObject.isEnum() -> ClassKind.ENUM_CLASS
                        classOrObject.isAnnotation() -> ClassKind.ANNOTATION_CLASS
                        else -> ClassKind.CLASS
                    }
                    else -> throw AssertionError("Unexpected class or object: ${classOrObject.text}")
                }
                val firClass = FirClassImpl(
                    session,
                    classOrObject,
                    FirClassSymbol(currentClassId),
                    classOrObject.nameAsSafeName,
                    classOrObject.visibility,
                    classOrObject.modality,
                    classOrObject.hasExpectModifier(),
                    classOrObject.hasActualModifier(),
                    classKind,
                    isInner = classOrObject.hasModifier(KtTokens.INNER_KEYWORD),
                    isCompanion = (classOrObject as? KtObjectDeclaration)?.isCompanion() == true,
                    isData = (classOrObject as? KtClass)?.isData() == true,
                    isInline = classOrObject.hasModifier(KtTokens.INLINE_KEYWORD)
                )
                classOrObject.extractAnnotationsTo(firClass)
                classOrObject.extractTypeParametersTo(firClass)
                val delegatedSelfType = classOrObject.toDelegatedSelfType()
                val delegatedSuperType = classOrObject.extractSuperTypeListEntriesTo(firClass, delegatedSelfType)
                classOrObject.primaryConstructor?.valueParameters?.forEach {
                    if (it.hasValOrVar()) {
                        firClass.declarations += it.toFirProperty()
                    }
                }

                for (declaration in classOrObject.declarations) {
                    firClass.declarations += when (declaration) {
                        is KtSecondaryConstructor -> declaration.toFirConstructor(
                            delegatedSuperType,
                            delegatedSelfType,
                            classOrObject.primaryConstructor != null
                        )
                        else -> declaration.convert<FirDeclaration>()
                    }
                }

                firClass
            }
        }

        override fun visitTypeAlias(typeAlias: KtTypeAlias, data: Unit): FirElement {
            return withChildClassName(typeAlias.nameAsSafeName) {
                val firTypeAlias = FirTypeAliasImpl(
                    session,
                    typeAlias,
                    FirTypeAliasSymbol(currentClassId),
                    typeAlias.nameAsSafeName,
                    typeAlias.visibility,
                    typeAlias.hasExpectModifier(),
                    typeAlias.hasActualModifier(),
                    typeAlias.getTypeReference().toFirOrErrorType()
                )
                typeAlias.extractAnnotationsTo(firTypeAlias)
                typeAlias.extractTypeParametersTo(firTypeAlias)
                firTypeAlias
            }
        }

        private val firFunctions = mutableListOf<FirFunction>()

        private fun <T> MutableList<T>.removeLast() {
            removeAt(size - 1)
        }

        override fun visitNamedFunction(function: KtNamedFunction, data: Unit): FirElement {
            if (function.name == null) {
                // TODO: return anonymous function here
                // TODO: what if name is not null but we're in expression position?
                return FirExpressionStub(session, function)
            }
            val typeReference = function.typeReference
            val firFunction = FirMemberFunctionImpl(
                session,
                function,
                function.nameAsSafeName,
                function.visibility,
                function.modality,
                function.hasExpectModifier(),
                function.hasActualModifier(),
                function.hasModifier(KtTokens.OVERRIDE_KEYWORD),
                function.hasModifier(KtTokens.OPERATOR_KEYWORD),
                function.hasModifier(KtTokens.INFIX_KEYWORD),
                function.hasModifier(KtTokens.INLINE_KEYWORD),
                function.hasModifier(KtTokens.TAILREC_KEYWORD),
                function.hasModifier(KtTokens.EXTERNAL_KEYWORD),
                function.hasModifier(KtTokens.SUSPEND_KEYWORD),
                function.receiverTypeReference.convertSafe(),
                if (function.hasBlockBody()) {
                    typeReference.toFirOrUnitType()
                } else {
                    typeReference.toFirOrImplicitType()
                }
            )
            firFunctions += firFunction
            function.extractAnnotationsTo(firFunction)
            function.extractTypeParametersTo(firFunction)
            for (valueParameter in function.valueParameters) {
                firFunction.valueParameters += valueParameter.convert<FirValueParameter>()
            }
            firFunction.body = function.buildFirBody()
            firFunctions.removeLast()
            return firFunction
        }

        private fun KtSecondaryConstructor.toFirConstructor(
            delegatedSuperType: FirType?,
            delegatedSelfType: FirType,
            hasPrimaryConstructor: Boolean
        ): FirConstructor {
            val firConstructor = FirConstructorImpl(
                session,
                this,
                visibility,
                hasExpectModifier(),
                hasActualModifier(),
                delegatedSelfType,
                getDelegationCall().convert(delegatedSuperType, delegatedSelfType, hasPrimaryConstructor)
            )
            firFunctions += firConstructor
            extractAnnotationsTo(firConstructor)
            extractValueParametersTo(firConstructor)
            firConstructor.body = buildFirBody()
            firFunctions.removeLast()
            return firConstructor
        }

        private fun KtConstructorDelegationCall.convert(
            delegatedSuperType: FirType?,
            delegatedSelfType: FirType,
            hasPrimaryConstructor: Boolean
        ): FirDelegatedConstructorCall {
            val isThis = isCallToThis || (isImplicit && hasPrimaryConstructor)
            val delegatedType = when {
                isThis -> delegatedSelfType
                else -> delegatedSuperType ?: FirErrorTypeImpl(session, this, "No super type")
            }
            return FirDelegatedConstructorCallImpl(
                session,
                this,
                delegatedType,
                isThis
            ).apply {
                if (!stubMode) {
                    extractArgumentsTo(this)
                }
            }
        }

        override fun visitAnonymousInitializer(initializer: KtAnonymousInitializer, data: Unit): FirElement {
            return FirAnonymousInitializerImpl(
                session,
                initializer,
                FirBlockImpl(session, initializer)
            )
        }

        override fun visitProperty(property: KtProperty, data: Unit): FirElement {
            val propertyType = property.typeReference.toFirOrImplicitType()
            val name = property.nameAsSafeName
            val isVar = property.isVar
            val initializer = if (property.hasInitializer()) {
                { property.initializer }.toFirExpression("Should have initializer")
            } else null
            val firProperty = if (property.isLocal) {
                FirVariableImpl(
                    session,
                    property,
                    name,
                    propertyType,
                    isVar,
                    initializer
                )
            } else {
                FirMemberPropertyImpl(
                    session,
                    property,
                    name,
                    property.visibility,
                    property.modality,
                    property.hasExpectModifier(),
                    property.hasActualModifier(),
                    property.hasModifier(KtTokens.OVERRIDE_KEYWORD),
                    property.hasModifier(KtTokens.CONST_KEYWORD),
                    property.hasModifier(KtTokens.LATEINIT_KEYWORD),
                    property.receiverTypeReference.convertSafe(),
                    propertyType,
                    isVar,
                    initializer,
                    property.getter.toFirPropertyAccessor(property, propertyType, isGetter = true),
                    property.setter.toFirPropertyAccessor(property, propertyType, isGetter = false),
                    if (property.hasDelegate()) FirExpressionStub(session, property) else null
                ).apply {
                    property.extractTypeParametersTo(this)
                }
            }
            property.extractAnnotationsTo(firProperty)
            return firProperty
        }

        override fun visitTypeReference(typeReference: KtTypeReference, data: Unit): FirElement {
            val typeElement = typeReference.typeElement
            val isNullable = typeElement is KtNullableType

            fun KtTypeElement?.unwrapNullable(): KtTypeElement? =
                if (this is KtNullableType) this.innerType.unwrapNullable() else this

            val unwrappedElement = typeElement.unwrapNullable()
            val firType = when (unwrappedElement) {
                is KtDynamicType -> FirDynamicTypeImpl(session, typeReference, isNullable)
                is KtUserType -> {
                    var referenceExpression = unwrappedElement.referenceExpression
                    if (referenceExpression != null) {
                        val userType = FirUserTypeImpl(
                            session, typeReference, isNullable
                        )
                        var qualifier: KtUserType? = unwrappedElement
                        do {
                            val firQualifier = FirQualifierPartImpl(referenceExpression!!.getReferencedNameAsName())
                            for (typeArgument in qualifier!!.typeArguments) {
                                firQualifier.typeArguments += typeArgument.convert<FirTypeProjection>()
                            }
                            userType.qualifier.add(firQualifier)

                            qualifier = qualifier.qualifier
                            referenceExpression = qualifier?.referenceExpression
                        } while (referenceExpression != null)

                        userType.qualifier.reverse()

                        userType
                    } else {
                        FirErrorTypeImpl(session, typeReference, "Incomplete user type")
                    }
                }
                is KtFunctionType -> {
                    val functionType = FirFunctionTypeImpl(
                        session,
                        typeReference,
                        isNullable,
                        unwrappedElement.receiverTypeReference.convertSafe(),
                        // TODO: probably implicit type should not be here
                        unwrappedElement.returnTypeReference.toFirOrImplicitType()
                    )
                    for (valueParameter in unwrappedElement.parameters) {
                        functionType.valueParameters += valueParameter.convert<FirValueParameter>()
                    }
                    functionType
                }
                null -> FirErrorTypeImpl(session, typeReference, "Unwrapped type is null")
                else -> throw AssertionError("Unexpected type element: ${unwrappedElement.text}")
            }

            for (annotationEntry in typeReference.annotationEntries) {
                firType.annotations += annotationEntry.convert<FirAnnotationCall>()
            }
            return firType
        }

        override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry, data: Unit): FirElement {
            val firAnnotationCall = FirAnnotationCallImpl(
                session,
                annotationEntry,
                annotationEntry.useSiteTarget?.getAnnotationUseSiteTarget(),
                annotationEntry.typeReference.toFirOrErrorType()
            )
            annotationEntry.extractArgumentsTo(firAnnotationCall)
            return firAnnotationCall
        }

        override fun visitTypeParameter(parameter: KtTypeParameter, data: Unit): FirElement {
            val firTypeParameter = FirTypeParameterImpl(
                session,
                parameter,
                FirTypeParameterSymbol(),
                parameter.nameAsSafeName,
                parameter.variance,
                parameter.hasModifier(KtTokens.REIFIED_KEYWORD)
            )
            parameter.extractAnnotationsTo(firTypeParameter)
            val extendsBound = parameter.extendsBound
            // TODO: handle where, here or (preferable) in parent
            if (extendsBound != null) {
                firTypeParameter.bounds += extendsBound.convert<FirType>()
            }
            return firTypeParameter
        }

        override fun visitTypeProjection(typeProjection: KtTypeProjection, data: Unit): FirElement {
            val projectionKind = typeProjection.projectionKind
            if (projectionKind == KtProjectionKind.STAR) {
                return FirStarProjectionImpl(session, typeProjection)
            }
            val typeReference = typeProjection.typeReference
            val firType = typeReference.toFirOrErrorType()
            return FirTypeProjectionWithVarianceImpl(
                session,
                typeProjection,
                when (projectionKind) {
                    KtProjectionKind.IN -> Variance.IN_VARIANCE
                    KtProjectionKind.OUT -> Variance.OUT_VARIANCE
                    KtProjectionKind.NONE -> Variance.INVARIANT
                    KtProjectionKind.STAR -> throw AssertionError("* should not be here")
                },
                firType
            )
        }

        override fun visitParameter(parameter: KtParameter, data: Unit): FirElement =
            parameter.toFirValueParameter()

        override fun visitBlockExpression(expression: KtBlockExpression, data: Unit): FirElement {
            return FirBlockImpl(session, expression).apply {
                for (statement in expression.statements) {
                    statements += statement.toFirStatement("Statement expected: ${statement.text}")
                }
            }
        }

        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression, data: Unit): FirElement {
            return FirPropertyGetImpl(session, expression).apply {
                calleeReference = FirSimpleMemberReference(
                    session, expression.getReferencedNameElement(), expression.getReferencedNameAsName()
                )
            }
        }

        override fun visitConstantExpression(expression: KtConstantExpression, data: Unit): FirElement {
            val type = expression.node.elementType
            val text: String = expression.text
            return when (type) {
                KtNodeTypes.INTEGER_CONSTANT ->
                    if (text.last() == 'l' || text.last() == 'L') {
                        FirConstExpressionImpl(
                            session, expression, IrConstKind.Long, text.dropLast(1).toLongOrNull(), "Incorrect long: $text"
                        )
                    } else {
                        // TODO: support byte / short
                        FirConstExpressionImpl(session, expression, IrConstKind.Int, text.toIntOrNull(), "Incorrect int: $text")
                    }
                KtNodeTypes.FLOAT_CONSTANT ->
                    if (text.last() == 'f' || text.last() == 'F') {
                        FirConstExpressionImpl(
                            session, expression, IrConstKind.Float, text.dropLast(1).toFloatOrNull(), "Incorrect float: $text"
                        )
                    } else {
                        FirConstExpressionImpl(
                            session, expression, IrConstKind.Double, text.toDoubleOrNull(), "Incorrect double: $text"
                        )
                    }
                KtNodeTypes.CHARACTER_CONSTANT ->
                    FirConstExpressionImpl(
                        session, expression, IrConstKind.Char, text.parseCharacter(), "Incorrect character: $text"
                    )
                KtNodeTypes.BOOLEAN_CONSTANT ->
                    FirConstExpressionImpl(session, expression, IrConstKind.Boolean, text.toBoolean())
                KtNodeTypes.NULL ->
                    FirConstExpressionImpl(session, expression, IrConstKind.Null, null)
                else ->
                    throw AssertionError("Unknown literal type: $type, $text")
            }
        }

        override fun visitStringTemplateExpression(expression: KtStringTemplateExpression, data: Unit): FirElement {
            val sb = StringBuilder()
            for (entry in expression.entries) {
                when (entry) {
                    is KtLiteralStringTemplateEntry -> sb.append(entry.text)
                    is KtEscapeStringTemplateEntry -> sb.append(entry.unescapedValue)
                    else -> return FirErrorExpressionImpl(session, expression, "Incorrect template entry: ${entry.text}")
                }
            }
            return FirConstExpressionImpl(session, expression, IrConstKind.String, sb.toString())
        }

        override fun visitReturnExpression(expression: KtReturnExpression, data: Unit): FirElement {
            val result = expression.returnedExpression?.toFirExpression() ?: FirUnitExpression(session, expression)
            return result.toReturn(expression.getTargetLabel()?.getReferencedName())
        }

        override fun visitIfExpression(expression: KtIfExpression, data: Unit): FirElement {
            return FirWhenExpressionImpl(
                session,
                expression
            ).apply {
                val condition = expression.condition
                val firCondition = condition.toFirExpression("If statement should have condition")
                val trueBranch = expression.then.toFirBlock()
                branches += FirWhenBranchImpl(session, condition, firCondition, trueBranch)
                val elseBranch = expression.`else`.toFirBlock()
                branches += FirWhenBranchImpl(session, null, FirElseIfTrueCondition(session, null), elseBranch)
            }
        }

        private fun KtWhenCondition.toFirWhenCondition(firSubjectExpression: FirExpression): FirExpression {
            return when (this) {
                is KtWhenConditionWithExpression -> {
                    val expressionInCondition = expression
                    FirOperatorCallImpl(
                        session,
                        expressionInCondition,
                        FirOperation.EQ
                    ).apply {
                        arguments += firSubjectExpression
                        arguments += expressionInCondition.toFirExpression("No expression in condition with expression")
                    }
                }
                is KtWhenConditionInRange -> {
                    val rangeExpression = rangeExpression
                    FirOperatorCallImpl(
                        session,
                        rangeExpression,
                        if (isNegated) FirOperation.NOT_IN else FirOperation.IN
                    ).apply {
                        arguments += firSubjectExpression
                        arguments += rangeExpression.toFirExpression("No range in condition with range")
                    }
                }
                // TODO: support KtWhenConditionIsPattern
                else -> {
                    FirErrorExpressionImpl(session, this, "Unsupported when condition: ${this.javaClass}")
                }
            }
        }

        override fun visitWhenExpression(expression: KtWhenExpression, data: Unit): FirElement {
            val subjectExpression = expression.subjectExpression
            val subject = when (subjectExpression) {
                is KtVariableDeclaration -> subjectExpression.initializer
                else -> subjectExpression
            }?.toFirExpression()
            val subjectVariable = when (subjectExpression) {
                is KtVariableDeclaration -> FirVariableImpl(
                    session, subjectExpression, subjectExpression.nameAsSafeName,
                    subjectExpression.typeReference.toFirOrImplicitType(),
                    isVar = false, initializer = subject
                )
                else -> null
            }
            val hasSubject = subject != null
            return FirWhenExpressionImpl(
                session,
                expression,
                subject,
                subjectVariable
            ).apply {
                for (entry in expression.entries) {
                    val branch = entry.expression.toFirBlock()
                    branches += if (!entry.isElse) {
                        if (hasSubject) {
                            var firCondition: FirExpression? = null
                            val firSubjectExpression = FirWhenSubjectExpression(session, subjectExpression)
                            for (condition in entry.conditions) {
                                val firConditionElement = condition.toFirWhenCondition(firSubjectExpression)
                                when {
                                    firCondition == null -> firCondition = firConditionElement
                                    firCondition is FirOperatorCallImpl && firCondition.operation == FirOperation.OR -> {
                                        firCondition.arguments += firConditionElement
                                    }
                                    else -> {
                                        firCondition = FirOperatorCallImpl(session, entry, FirOperation.OR).apply {
                                            arguments += firCondition!!
                                            arguments += firConditionElement
                                        }
                                    }
                                }
                            }
                            FirWhenBranchImpl(session, entry, firCondition!!, branch)
                        } else {
                            val condition = entry.conditions.first() as KtWhenConditionWithExpression
                            val firCondition = condition.expression.toFirExpression("No expression in condition with expression")
                            FirWhenBranchImpl(session, condition, firCondition, branch)
                        }
                    } else {
                        FirWhenBranchImpl(session, null, FirElseIfTrueCondition(session, null), branch)
                    }
                }
            }
        }

        private fun IElementType.toName(): Name? {
            return OperatorConventions.BINARY_OPERATION_NAMES[this]
        }

        private fun IElementType.toFirOperation(): FirOperation =
            when (this) {
                KtTokens.LT -> FirOperation.LT
                KtTokens.GT -> FirOperation.GT
                KtTokens.LTEQ -> FirOperation.LT_EQ
                KtTokens.GTEQ -> FirOperation.GT_EQ
                KtTokens.EQEQ -> FirOperation.EQ
                KtTokens.EXCLEQ -> FirOperation.NOT_EQ
                KtTokens.EQEQEQ -> FirOperation.IDENTITY
                KtTokens.EXCLEQEQEQ -> FirOperation.NOT_IDENTITY
                KtTokens.ANDAND -> FirOperation.AND
                KtTokens.OROR -> FirOperation.OR
                KtTokens.IN_KEYWORD -> FirOperation.IN
                KtTokens.NOT_IN -> FirOperation.NOT_IN
                KtTokens.RANGE -> FirOperation.RANGE

                KtTokens.EQ -> FirOperation.ASSIGN
                KtTokens.PLUSEQ -> FirOperation.PLUS_ASSIGN
                KtTokens.MINUSEQ -> FirOperation.MINUS_ASSIGN
                KtTokens.MULTEQ -> FirOperation.TIMES_ASSIGN
                KtTokens.DIVEQ -> FirOperation.DIV_ASSIGN
                KtTokens.PERCEQ -> FirOperation.REM_ASSIGN

                else -> throw AssertionError(this.toString())
            }

        private fun KtBinaryExpression.elvisToWhen(): FirWhenExpression {
            val rightArgument = right.toFirExpression("No right operand")
            val leftArgument = left.toFirExpression("No left operand")
            val subjectName = Name.special("<elvis>")
            val subjectVariable = FirVariableImpl(
                session, left, subjectName,
                FirImplicitTypeImpl(session, left), false, leftArgument
            )
            val subjectExpression = FirWhenSubjectExpression(session, this)
            return FirWhenExpressionImpl(
                session, this, leftArgument, subjectVariable
            ).apply {
                branches += FirWhenBranchImpl(
                    session, left,
                    FirOperatorCallImpl(session, left, FirOperation.NOT_EQ).apply {
                        arguments += subjectExpression
                        arguments += FirConstExpressionImpl(session, left, IrConstKind.Null, null)
                    },
                    FirSingleExpressionBlock(
                        session,
                        FirPropertyGetImpl(session, left).apply {
                            calleeReference = FirSimpleMemberReference(
                                session, left, subjectName
                            )
                        }
                    )
                )
                branches += FirWhenBranchImpl(
                    session, right, FirElseIfTrueCondition(session, this@elvisToWhen),
                    FirSingleExpressionBlock(session, rightArgument)
                )
            }
        }

        override fun visitBinaryExpression(expression: KtBinaryExpression, data: Unit): FirElement {
            val operationToken = expression.operationToken
            val rightArgument = expression.right.toFirExpression("No right operand")
            if (operationToken == KtTokens.ELVIS) {
                return expression.elvisToWhen()
            }
            val conventionCallName = operationToken.toName()
            return if (conventionCallName != null || operationToken == KtTokens.IDENTIFIER) {
                FirFunctionCallImpl(
                    session, expression
                ).apply {
                    calleeReference = FirSimpleMemberReference(
                        session, expression.operationReference,
                        conventionCallName ?: expression.operationReference.getReferencedNameAsName()
                    )
                }
            } else {
                val firOperation = operationToken.toFirOperation()
                if (firOperation in FirOperation.ASSIGNMENTS) {
                    return FirPropertySetImpl(session, expression, rightArgument, firOperation).apply {
                        val left = expression.left
                        if (left is KtSimpleNameExpression) {
                            calleeReference = FirSimpleMemberReference(
                                session, left.getReferencedNameElement(), left.getReferencedNameAsName()
                            )
                        } else {
                            // TODO: array accesses etc.
                            calleeReference = FirErrorMemberReference(
                                session, left, "Unsupported LValue: ${left?.javaClass}"
                            )
                        }
                    }
                } else {
                    FirOperatorCallImpl(
                        session, expression, firOperation
                    )
                }
            }.apply {
                arguments += expression.left.toFirExpression("No left operand")
                arguments += rightArgument
            }
        }

        override fun visitCallExpression(expression: KtCallExpression, data: Unit): FirElement {
            val calleeExpression = expression.calleeExpression
            return FirFunctionCallImpl(session, expression).apply {
                val calleeReference = when (calleeExpression) {
                    is KtSimpleNameExpression -> FirSimpleMemberReference(
                        session, calleeExpression, calleeExpression.getReferencedNameAsName()
                    )
                    null -> FirErrorMemberReference(
                        session, calleeExpression, "Call has no callee"
                    )
                    else -> {
                        arguments += calleeExpression.toFirExpression()
                        FirSimpleMemberReference(
                            session, expression, OperatorNameConventions.INVOKE
                        )
                    }
                }
                this.calleeReference = calleeReference
                for (argument in expression.valueArguments) {
                    arguments += argument.getArgumentExpression().toFirExpression("No argument expression")
                }
            }
        }

        override fun visitExpression(expression: KtExpression, data: Unit): FirElement {
            return FirExpressionStub(session, expression)
        }
    }
}