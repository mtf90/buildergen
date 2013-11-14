package io.github.misberner.buildergen.processor;

import io.github.misberner.buildergen.annotations.GenerateBuilder;
import io.github.misberner.buildergen.annotations.Option;
import io.github.misberner.buildergen.annotations.Visibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;


public class SpecModel {
	
	private final String name;
	private final String packageName;
	private final ExecutableElement instantiator;
	private final DeclaredType instanceType;
	private final DefaultsModel defaults;
	
	private final boolean classFinal;
	private final boolean classPublic;
	
	private final String getterPrefix;
	private final String getterPrefixBool;
	private final String setterPrefix;
	private final String withPrefix;
	
	private final String createInvocation;
	private final List<? extends TypeParameterElement> typeParameters;
	
	private final List<OptionModel> options = new ArrayList<>(); 
	
	private final String createName;
	
	
	
	public SpecModel(ExecutableElement instantiator, ProcessingEnvironment processingEnv, Util util) {
		AnnotationMirror annMirror = util.findAnnotationMirror(instantiator, GenerateBuilderProcessor.GENERATE_BUILDER_NAME);
		GenerateBuilder ann = instantiator.getAnnotation(GenerateBuilder.class);
		
		if(annMirror == null || ann == null) {
			processingEnv.getMessager().printMessage(Kind.WARNING, "Unable to find annotation " + GenerateBuilderProcessor.GENERATE_BUILDER_NAME, instantiator);
			throw new IllegalArgumentException();
		}
		
		this.instantiator = instantiator;
		
		DeclaredType instanceType;
		if(instantiator.getKind() == ElementKind.METHOD) {
			if(!instantiator.getModifiers().contains(Modifier.STATIC)) {
				processingEnv.getMessager().printMessage(Kind.ERROR, "@GenerateBuilder can only be applied to constructors or static methods", instantiator, annMirror);
				throw new IllegalArgumentException();
			}
			TypeMirror ret = instantiator.getReturnType();
			if(ret.getKind() != TypeKind.DECLARED) {
				processingEnv.getMessager().printMessage(Kind.ERROR, "@GenerateBuilder can only be applied to static methods with a class or interface return type", instantiator);
				throw new IllegalArgumentException();
			}
			instanceType = (DeclaredType)ret;
			this.typeParameters = instantiator.getTypeParameters();
			
			TypeElement te = (TypeElement)instantiator.getEnclosingElement();
			this.createInvocation = te.getQualifiedName().toString() + "." + instantiator.getSimpleName().toString();
		}
		else {
			TypeElement te = (TypeElement)instantiator.getEnclosingElement();
			instanceType = (DeclaredType)te.asType();
			List<TypeParameterElement> params = new ArrayList<>();
			
			this.createInvocation = "new " + instanceType.toString();
			params.addAll(te.getTypeParameters());
			params.addAll(instantiator.getTypeParameters());
			this.typeParameters = params;
		}
		this.instanceType = instanceType;
		
		
		
		String name = ann.name();
		if(name.isEmpty()) {
			name = ann.value();
			if(name.isEmpty()) {
				name = instanceType.asElement().getSimpleName() + "Builder";
			}
		}
		this.name = name;
		
		String packageName = ann.packageName();
		if(packageName.isEmpty()) {
			packageName = util.getPackageName(instantiator);
		}
		if(packageName.isEmpty()) {
			packageName = null;
		}
		this.packageName = packageName;
		
		this.classPublic = ann.builderPublic();
		this.classFinal = ann.builderFinal();
		
		String[] getterPrefixes = ann.getterPrefix();
		if(getterPrefixes.length == 0) {
			this.getterPrefix = null;
			this.getterPrefixBool = null;
		}
		else {
			this.getterPrefix = getterPrefixes[0];
			if(getterPrefixes.length > 1) {
				this.getterPrefixBool = this.getterPrefix;
			}
			else {
				this.getterPrefixBool = getterPrefixes[1];
				if(getterPrefixes.length > 2) {
					processingEnv.getMessager().printMessage(Kind.WARNING, "More than two getter prefixes specified", instantiator, annMirror);
				}
			}
		}
		
		String setterPrefix = ann.setterPrefix();
		this.setterPrefix = "".equals(setterPrefix) ? null : setterPrefix;
		
		String withPrefix = ann.withPrefix();
		this.withPrefix = "".equals(withPrefix) ? null : withPrefix;
		
		
		this.createName = ann.createName();
		
		Map<String,Object> annValues = util.getAnnotationValues(annMirror);
		
		TypeMirror defaultsType = (TypeMirror)annValues.get("defaults");
		
		TypeElement defaultsTypeElem = null;
		if(defaultsType.getKind() == TypeKind.DECLARED) {
			DeclaredType dt = (DeclaredType)defaultsType;
			TypeElement te = (TypeElement)dt.asElement();
			if(!te.getQualifiedName().contentEquals(Void.class.getName())) {
				defaultsTypeElem = te;
			}
		}
		if(defaultsTypeElem == null) {
			Map<String,? extends TypeElement> enclosedTypes = util.getEnclosedElements(instantiator.getEnclosingElement(), TypeElement.class);
			defaultsTypeElem = enclosedTypes.get("$BuilderDefauls");
		}
		
		this.defaults = DefaultsModel.create(defaultsTypeElem, processingEnv);
		
		
		processOptions(util);
	}
	
	
	
	private void processOptions(Util util) {
		for(VariableElement ve : instantiator.getParameters()) {
			OptionModel optModel = processOption(util, ve);
			this.options.add(optModel);
		}
	}
	
	private OptionModel processOption(Util util, VariableElement ve) {
		Map<String,Object> annValues = util.getAnnotationValues(ve, Option.class.getName());
		String name = "";
		if(annValues != null) {
			name = (String)annValues.get("name");
			
			if("".equals(name)) {
				name = (String)annValues.get("value");
			}
		}
		if("".equals(name)) {
			name = ve.getSimpleName().toString();
		}
		
		String defaultExpr = "";
		if(annValues != null) {
			defaultExpr = (String)annValues.get("defaultExpr");
		}
		if("".equals(defaultExpr)) {
			defaultExpr = defaults.getDefaultsExpression(name);
		}
		
		
		TypeMirror type = ve.asType();
		TypeKind tk = type.getKind();
		String getterName = "";
		if(annValues != null) {
			getterName = (String)annValues.get("getterName");
		}
		if("".equals(getterName)) {
			getterName = getterName(name, tk);
		}
		
		String setterName = "";
		if(annValues != null) {
			setterName = (String)annValues.get("setterName");
		}
		if("".equals(setterName)) {
			setterName = setterName(name, tk);
		}
		
		String withName = "";
		if(annValues != null) {
			withName = (String)annValues.get("withName");
		}
		if(withName.isEmpty()) {
			withName = withName(name, tk);
		}
		
		OptionModel optModel = new OptionModel(type, name, getterName, setterName, withName, defaultExpr, Visibility.PRIVATE);
		
		return optModel;
	}
	
	private String getterName(String varName, TypeKind kind) {
		String prefix = (kind == TypeKind.BOOLEAN) ? "is" : "get";
		String capName = prefix.isEmpty() ? varName : capitalize(varName);
		return prefix + capName;
	}
	
	private String setterName(String varName, TypeKind kind) {
		String prefix = "set";
		String capName = prefix.isEmpty() ? varName : capitalize(varName);
		return prefix + capName;
	}
	
	private String withName(String varName, TypeKind kind) {
		String prefix = "with";
		String capName = prefix.isEmpty() ? varName : capitalize(varName);
		return prefix + capName;
	}
	
	private static String capitalize(String str) {
		if(str.isEmpty())
			return str;
		return Character.toUpperCase(str.charAt(0)) + str.substring(1);
	}


	public String getName() {
		return name;
	}





	public String getPackageName() {
		return packageName;
	}





	public TypeMirror getInstanceType() {
		return instanceType;
	}




	public boolean isClassFinal() {
		return classFinal;
	}





	public boolean isClassPublic() {
		return classPublic;
	}





	public List<OptionModel> getOptions() {
		return options;
	}


	public List<? extends TypeParameterElement> getTypeParameters() {
		return typeParameters;
	}
	
	public ExecutableElement getInstantiator() {
		return instantiator;
	}

	
	public String getCreateInvocation() {
		return createInvocation;
	}

	
	public String getCreateName() {
		return createName;
	}

	public String getFullName() {
		if(packageName == null)
			return name;
		return packageName + "." + name;
	}
	
}