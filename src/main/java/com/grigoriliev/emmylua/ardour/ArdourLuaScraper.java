package com.grigoriliev.emmylua.ardour;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class ArdourLuaScraper {
	public static final Set<String> ARDOUR_GLOBAL_VARIABLES = Set.of("Session", "Editor");
	public static final Properties CLASS_DOC_PROPERTIES = new Properties();
	public static final Properties FUNCTION_DOC_PROPERTIES = new Properties();

	public static void main(String[] args) {
		if (args.length != 1) {
			System.out.println("Please, specify output file");
			return;
		}
		try {
			CLASS_DOC_PROPERTIES.load(
				ArdourLuaScraper.class.getResourceAsStream("/classdoc.properties")
			);
			FUNCTION_DOC_PROPERTIES.load(
				ArdourLuaScraper.class.getResourceAsStream("/functiondoc.properties")
			);
			final Document doc = Jsoup.connect(
				"https://manual.ardour.org/lua-scripting/class_reference/"
			).get();

			final String pre = "--[[\n\n" +
				new String(
					ArdourLuaScraper.class.getResourceAsStream("/LICENSE").readAllBytes(),
					StandardCharsets.UTF_8
				)+
				"\n--]]\n\n" +
				"-- This is an AUTOMATICALLY generated file by web-scraping\n" +
				"-- https://manual.ardour.org/lua-scripting/class_reference/\n\n";

			Files.write(
				Paths.get(args[0]),
				(pre + exportEmmyLuaAnnotations(doc)).getBytes()
			);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static Stream<LuaClass> getNamespaceStream(Document doc) {
		return doc.select("#luaref").stream().flatMap(
			element -> element.children().stream()
		).filter(
			element -> "h3".equals(element.tagName()) &&
				element.hasClass(LuaClass.Kind.NAMESPACE.getCssClass())
		).map(LuaClass::new);
	}

	private static Stream<LuaClass> getClassDefStream(Document doc) {
		return doc.select("#luaref").stream().flatMap(
			element -> element.children().stream()
		).filter(
			element -> "h3".equals(element.tagName()) &&
			Stream.of(LuaClass.Kind.values()).map(LuaClass.Kind::getCssClass)
				.anyMatch(element::hasClass)
		).map(LuaClass::new);
	}

	private static Stream<LuaEnum> getEnumStream(Document doc) {
		return JSoupUtil.find(
			doc.select("#luaref").stream().flatMap(
				element -> element.children().stream()
			),
			el -> el.hasClass("enum") && "h3".equals(el.tagName()),
			el -> "Enum/Constants".equals(el.text()) && "h2".equals(el.tagName()),
			el -> false
		).map(
			el -> {
				final Element ulEl = el.nextElementSibling();
				if (!"ul".equals(ulEl.tagName()) || !ulEl.hasClass("enum")) {
					throw new IllegalStateException();
				}
				return new LuaEnum(
					getEnumType(el),
					ulEl.children().stream().map(
						liEl -> {
							if (!"li".equals(liEl.tagName()) || !liEl.hasClass("const")) {
								throw new IllegalStateException();
							}
							return adjustEnum(liEl.text());
						}
					).collect(Collectors.toList())
				);
			}
		);
	}

	private static String adjustEnum(String name) {
		if(name.endsWith(","))  {
			return name.substring(0, name.length() - 1);
		}
		return name;
	}

	private static String getEnumType(Element element) {
		String result = element.id();
		if (result.endsWith("<long>*")) {
			result = result.substring(0, result.length() - "<long>*".length());
		}
		return result;
	}

	public static String exportEmmyLuaAnnotations(Document doc) {
		final StringBuilder buf = new StringBuilder();
		exportEmmyLuaAnnotations(getEnumStream(doc), getClassDefStream(doc), buf);
		return buf.toString();
	}

	public static void exportEmmyLuaAnnotations(
			Stream<LuaEnum> luaEnumStream, Stream<LuaClass> luaClassStream, StringBuilder buf
	) {
		final List<LuaClass> luaClasses = luaClassStream.sorted(
			Comparator.comparingInt(luaClass -> luaClass.getName().length())
		).collect(Collectors.toList());

		final Map<String, LuaClass> classMap = new HashMap<>();
		luaClasses.forEach(
			luaClass -> {
				final String ns = getNamespace(luaClass.getName());
				if (ns != null) {
					Optional.ofNullable(classMap.get(ns)).ifPresent(
						parent -> {
							parent.getNestedClasses().add(luaClass);
							luaClass.setParent(parent);
						}
					);
				}
				if (classMap.put(luaClass.getName(), luaClass) != null) {
					throw new IllegalStateException();
				}
			}
		);

		final List<LuaEnum> luaEnums = luaEnumStream.map(
			luaEnum -> {
				final String ns = getNamespace(luaEnum.type());
				final LuaClass parent = ns == null ? null : classMap.get(ns);
				if (parent != null) {
					parent.getNestedEnums().add(luaEnum);
					return luaEnum.withParent(parent);
				}
				return luaEnum;
			}
		).collect(Collectors.toList());

		appendGlobalVars(
			Stream.concat(
				luaEnums.stream().flatMap(
					luaEnum -> Stream.concat(
						Stream.of(luaEnum.type()),
						luaEnum.enumVars().stream()
					)
				).map(ArdourLuaScraper::getNamespace),
				luaClasses.stream().filter(luaClass -> luaClass.getParent() == null).map(
					luaClass -> luaClass.isNamespace() ?
						luaClass.getName() : getNamespace(luaClass.getName())
				)
			).filter(Objects::nonNull),
			buf
		);

		luaEnums.forEach(
			luaEnum -> {
				boolean isEnum = !classMap.containsKey(luaEnum.type());
				if (isEnum) {
					buf.append("---").append("This is an enum which can take one of the following values:\n");
					luaEnum.enumVars().forEach(
						var -> buf.append("--- * **").append(var).append("**\n")
					);
					luaEnum.enumVars().forEach(
						var -> buf.append("---@see ").append(var).append('\n')
					);
					buf.append("---@class ").append(luaEnum.type()).append('\n');
					buf.append(luaEnum.type()).append(" = {}\n\n");
				}

				if (isEnum) {
					luaEnum.enumVars().forEach(
						var -> buf.append("---This is an enum value of the following enum:").append('\n')
							.append("--- **").append(luaEnum.type()).append("**\n")
							.append("---@see ").append(luaEnum.type()).append('\n')
							.append("---@type ").append(luaEnum.type()).append('\n')
							.append(var).append(" = {}\n\n")
					);
				} else {
					luaEnum.enumVars().forEach(
						var -> buf.append("---This is a constant/enum.").append('\n')
							.append("---@see ").append(luaEnum.type()).append('\n')
							.append(var).append(" = {}\n\n")
					);
				}
			}
		);

		luaClasses.forEach(
			luaClass -> {
				appendEmmyLuaDoc(luaClass, buf);
				final String baseClass = luaClass.getBaseClassName();
				buf.append("---@class ").append(luaClass.getName()).append(
					baseClass.isEmpty() ? "" : " : " + baseClass
				).append("\n");
				luaClass.getLuaFields().forEach(
					field -> {
						buf.append("---@field ").append(field.name()).append(' ');
						final String luaType = toLuaType(field.type());
						buf.append(luaType);
						String comment = getTypeComment(field.type(), luaType);
						if (!field.doc().isBlank()) {
							comment += field.doc().lines().collect(Collectors.joining(" "));
						}
						buf.append(comment.isBlank() ? "\n" : " @" + comment + "\n");
					}
				);
				buf.append(
					ARDOUR_GLOBAL_VARIABLES.contains(luaClass.getName()) ||
						luaClass.getName().contains(".") ? "" : "local "
				).append(luaClass.getName()).append(" = {}\n");
				luaClass.getLuaFunctions().forEach(
					function -> appendEmmyLuaFunction(luaClass, function, buf)
				);
				buf.append("\n\n");
			}
		);
	}

	private static void appendGlobalVars(Stream<String> globalVarStream, StringBuilder buf) {
		final Map<String, Object> globalVarTree = new TreeMap<>();
		globalVarStream.map(name -> name.split("\\."))
			.forEach(
				path -> {
					Map<String, Object> map = globalVarTree;
					for (String el : path) {
						map = (Map<String, Object>) map.computeIfAbsent(el, key -> new TreeMap());
					}
				}
			);

		appendGlobalVars(List.of(), globalVarTree, buf);
	}

	private static void appendGlobalVars(
		List<String> pref, Map<String, Object> globalVarsMap, StringBuilder buf
	) {
		final String indent = IntStream.range(0, pref.size())
			.mapToObj(idx -> "\t").collect(Collectors.joining());
		globalVarsMap.forEach(
			(ns, map) -> {
				buf.append(indent).append("---@class ");
				final String nsPref = pref.stream().collect(Collectors.joining("."));
				if (!nsPref.isEmpty()) {
					buf.append(nsPref).append('.');
				}
				buf.append(ns).append('\n');
				buf.append(indent).append(ns).append(" = {\n");
				final List<String> newPref = new ArrayList<>(pref);
				newPref.add(ns);
				appendGlobalVars(newPref, (Map<String, Object>) map, buf);
				buf.append(indent).append(pref.isEmpty() ? "}\n" : "},\n");
			}
		);
		if (!globalVarsMap.isEmpty() && !pref.isEmpty()) {
			buf.delete(buf.length() - 2, buf.length() - 1);
		}
	}

	private static void appendEmmyLuaFunction(
		LuaClass luaClass, LuaFunction function, StringBuilder buf
	) {
		final String functionName = getFunctionName(luaClass, function);
		appendEmmyLuaDoc(functionName, function, buf);
		final List<String> params = getParamNames(
			function.arguments().stream().map(LuaField::type).collect(Collectors.toList())
		);
		IntStream.range(0, params.size()).forEach(
			idx -> {
				final String type = function.arguments().get(idx).type();
				final String luaType = toLuaType(type);
				params.get(idx);
				if (function.arguments().get(idx).name() != null) {
					params.set(idx, function.arguments().get(idx).name());
				}
				String comment = getTypeComment(type, luaType);
				if (function.arguments().get(idx).doc() != null) {
					comment += function.arguments().get(idx).doc().lines()
						.collect(Collectors.joining(" "));
				}
				buf.append("---@param ")
				.append(params.get(idx)).append(' ').append(luaType)
				.append(comment.isBlank() ? "\n" : " @" + comment + "\n");
			}
		);
		if (
			!"void".equals(function.returnType()) &&
			!"...".equals(function.returnType())
		) {
			final String luaType = function.isConstructor() ?
				luaClass.getName() : toLuaType(function.returnType());
			String comment = getTypeComment(function.returnType(), luaType);
			final String rd = function.returnDoc().lines().collect(Collectors.joining(" "));
			if (!rd.isEmpty()) {
				comment = (comment.isEmpty() ? "" : comment + " ") + rd;
			}
			buf.append("---@return ").append(luaType)
				.append(comment.isBlank() ? "\n" : " @" + comment + "\n");
		}

		buf.append("function ").append(functionName).append("(")
			.append(params.stream().collect(Collectors.joining(", ")))
			.append(") end\n\n");
	}

	private static String getNamespace(String var) {
		int idx = var.lastIndexOf('.');
		return idx == -1 ? null : var.substring(0, idx);
	}

	static String getFunctionName(LuaClass luaClass, LuaFunction function) {
		return getFunctionName(luaClass, function.name(), function.isConstructor());
	}

	static String getFunctionName(LuaClass luaClass, String functionName, boolean constructor) {
		if ((luaClass == null || luaClass.isNamespace()) && constructor) {
			throw new IllegalStateException();
		}
		if (constructor) {
			return luaClass.getName();
		}
		final String prefix = luaClass == null ?
			"" :
			luaClass.getName() + (luaClass.isNamespace() ? "." : ":");
		return prefix + functionName;
	}

	private static void appendEmmyLuaDoc(
		String fullFunctionName, LuaFunction luaFunction, StringBuilder buf
	) {
		appendEmmyLuaDoc(luaFunction.doc(), buf);
		Optional.ofNullable(FUNCTION_DOC_PROPERTIES.getProperty(fullFunctionName)).ifPresent(
			doc -> {
				buf.append("---\n--- User comments:\n");
				doc.lines().forEach(line -> buf.append("---").append(line).append("\n"));
			}
		);
	}

	private static void appendEmmyLuaDoc(LuaClass luaClass, StringBuilder buf) {
		appendEmmyLuaDoc(luaClass.getClassDoc(), buf);
		Optional.ofNullable(CLASS_DOC_PROPERTIES.getProperty(luaClass.getName())).ifPresent(
			doc -> {
				buf.append("---\n--- User comments:\n");
				doc.lines().forEach(line -> buf.append("---").append(line).append("\n"));
			}
		);
	}

	private static void appendEmmyLuaDoc(String doc, StringBuilder buf) {
		if (!doc.isBlank()) {
			doc.lines().forEach(
				line -> buf.append("---").append(line).append('\n')
			);
		};
	}

	private static List<String> getParamNames(List<String> types) {
		return IntStream.range(0, types.size())
			.mapToObj(idx -> toParamName(toLuaType(types.get(idx)), idx))
			.collect(Collectors.toList());
	}

	private static String toParamName(String type, int paramIdx) {
		String result = type;
		final int idx = result.indexOf('{');
		if (idx != -1) result = result.substring(0, idx).trim();

		result = result.substring(result.lastIndexOf('.') + 1);
		result = Character.toLowerCase(result.charAt(0)) +
			result.substring(1, result.length() - (result.endsWith("&") ? 1 : 0)) +
			(paramIdx + 1);
		return result;
	}

	private static String toLuaType(String cType) {
		final String type = cType.trim();
		if ("bool".equals(type) || "bool&".equals(type)) {
			return "boolean";
		}
		if (
			"std::string".equals(type) ||
			"char*".equals(type) ||
			"unsigned char*".equals(type) ||
			"char".equals(type) ||
			"unsigned char".equals(type)
		) return "string";
		if (
			"short".equals(type) ||
			"short&".equals(type) ||
			"unsigned short".equals(type) ||
			"unsigned short&".equals(type) ||
			"int".equals(type) ||
			"int&".equals(type) ||
			"unsigned int".equals(type) ||
			"unsigned int&".equals(type) ||
			"long".equals(type) ||
			"long&".equals(type) ||
			"unsigned long".equals(type) ||
			"unsigned long&".equals(type) ||
			"float".equals(type) ||
			"float&".equals(type) ||
			"double".equals(type) ||
			"double&".equals(type)
		) return "number";
		if ("--lua--".equals(type) || Character.isDigit(type.charAt(0))) return "unknown";
		if ("void*".equals(type)) return "userdata";
		if ("Lua-Function".equals(type)) return "function";
		if ("LuaIter".equals(type)) return "function";
		if ("LuaTable".equals(type) || type.startsWith("LuaTable {")) return "table";
		if ("LuaMetaTable".equals(type) || type.startsWith("LuaMetaTable {")) return "table";
		String result = type.replace("::", ".").replace(':', '.');
		return "ARDOUR.Session".equals(result) ? "Session" :
			"ArdourUI.Editor".equals(result) ? "Editor" : result;
	}

	private static String getTypeComment(String type, String luaType) {
		return type == null ?
			"(This is a constructor) " :
			"LuaIter".equals(type) ? "(LuaIter - an iterator for the collection)" :
			"LuaTable".equals(type) ? "(LuaTable)" :
			type.equals(luaType) ? "" : "(C type: " + type + ") ";
	}
}
