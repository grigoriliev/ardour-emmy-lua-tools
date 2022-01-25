package com.grigoriliev.emmylua.ardour;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

public class LuaClass {
	enum Kind {
		NAMESPACE("freeclass"),
		CLASS("class"),
		POINTER_CLASS("pointerclass"),
		OPAQUE_OBJECT("opaque"),
		ARRAY("array");

		private final String cssClass;
		public String getCssClass() { return cssClass; }

		Kind(String cssClass) {
			this.cssClass = cssClass;
		}
	}

	private final Kind kind;

	private final String name;
	public String getName() { return name; }

	private final String baseClassName;
	public String getBaseClassName() { return baseClassName; }

	private final List<LuaField> luaFields;
	public List<LuaField> getLuaFields() { return luaFields; }

	private final List<LuaFunction> luaFunctions;
	public List<LuaFunction> getLuaFunctions() { return luaFunctions; }

	private final String classDoc;
	public String getClassDoc() { return classDoc; }

	private final boolean namespace;
	public boolean isNamespace() { return namespace; }

	private LuaClass parent;
	public LuaClass getParent() { return parent; }
	public void setParent(LuaClass parent) { this.parent = parent; }

	private final List<LuaClass> nestedClasses = new ArrayList<>();
	public List<LuaClass> getNestedClasses() { return nestedClasses; }

	private final List<LuaEnum> nestedEnums = new ArrayList<>();
	public List<LuaEnum> getNestedEnums() { return nestedEnums; }

	private final List<LuaField> constants = new ArrayList<>();
	public List<LuaField> getConstants() { return constants; }

	public LuaClass(Element classElement) {
		final Set<String> classNames = classElement.classNames();
		kind = Stream.of(Kind.values())
			.filter(type -> classNames.contains(type.getCssClass()))
			.findFirst().orElseThrow();
		namespace = classElement.hasClass(Kind.NAMESPACE.getCssClass());
		String type = idToLuaType(classElement.id());

		// The top-level entry point are ARDOUR:Session and ArdourUI:Editor.
		if ("ARDOUR.Session".equals(type)) type = "Session";
		if ("ArdourUI.Editor".equals(type)) type = "Editor";

		name = type;

		if (kind != Kind.OPAQUE_OBJECT) {
			baseClassName = getBaseClass(classElement);
			final Element classMembersTable = JSoupUtil.findSibling(
				classElement,
				element -> element.hasClass("classmembers"),
				element -> "h3".equals(element.tagName())
			).orElseThrow(
				() -> new IllegalStateException("Can't find class members for " + classElement.id())
			);
			luaFields = getFields(classMembersTable);
			luaFunctions = getFunctions(classMembersTable);
		} else {
			baseClassName = "";
			luaFields = List.of();
			luaFunctions = List.of();
		}

		classDoc = getClassDoc(classElement);
	}

	@Override public String toString() {
		return "Class: " + name + "\n\tFunctions: " +
			luaFunctions.stream().map(Object::toString).collect(Collectors.joining(", "))
			+ "\n\t";
	}

	private static String getBaseClass(Element classElement) {
		return JSoupUtil.findSibling(
			classElement,
			element -> element.hasClass("classinfo"),
			element -> "h3".equals(element.tagName())
		).map(
			element -> {
				final List<TextNode> textNodes = element.textNodes();
				if (textNodes.size() != 1) {
					throw new IllegalStateException("Expected a text in classinfo");
				}
				final String txt = textNodes.get(0).text().trim();
				if (!"is-a:".equals(txt)) {
					throw new IllegalStateException("Expected 'is-a:' but was: " + txt);
				}
				return Optional.of(element.select("a"))
					.filter(elements -> elements.size() == 1)
					.map(elements -> elements.get(0))
					.map(Element::text)
					.map(line -> line.replace(':', '.'))
					.orElseThrow();
			}
		).orElse("");
	}

	private static List<LuaField> getFields(Element classMembersTable) {
		return JSoupUtil.find(
			classMembersTable.select("tr").stream(),
			LuaClass::isMemberDefElement,
			element -> "Data Members".equals(element.text()),
			element -> "h3".equals(element.tagName())
		).map(
			element -> new LuaField(
				element.child(1).child(0).text(),
				element.child(0).child(0).text(),
				getMemberDoc(element)
			)
		).collect(Collectors.toList());
	}

	private List<LuaFunction> getFunctions(Element classMembersTable) {
		return classMembersTable.select("tr").stream()
			.takeWhile(element -> !"Data Members".equals(element.text()))
			.filter(LuaClass::isFunctionDefElement)
			.map(
				element -> {
					final String functionName = element.child(1).child(0).text();
					final boolean constructor = isConstructor(element.child(0));
					final String fullFunctionName = ArdourLuaScraper.getFunctionName(
						this, functionName, constructor
					);
					return new LuaFunction(
						functionName,
						constructor ? null : getParamType(element.child(0).child(0)),
						getParams(fullFunctionName, element),
						getMemberDoc(element),
						getReturnDoc(fullFunctionName, element)
					);
				}
			).distinct().collect(Collectors.toList());
	}

	private static String getClassDoc(Element classElement) {
		return JSoupUtil.findSibling(
			classElement,
			element -> element.hasClass("classdox"),
			element -> "h3".equals(element.tagName())
		).map(Element::text).orElse("");
	}

	private static String getMemberDoc(Element element) {
		return Optional.ofNullable(element.nextElementSibling())
			.map(el -> el.select(".doc > .dox"))
			.filter(elements -> !elements.isEmpty())
			.map(
				elements -> {
					if (elements.size() > 1) throw new IllegalStateException();
					return elements.get(0).children().stream()
						.filter(el -> !isParamListElement(el) && !isResultDiscussionElement(el))
						.map(Element::text)
						.collect(Collectors.joining(" "));
				}
			).orElse("");
	}

	private static boolean isParamListElement(Element element) {
		return element.tagName().equalsIgnoreCase("dl") &&
			element.childrenSize() > 0 &&
			element.child(0).hasClass("param-name-index-0");
	}

	private static boolean isResultDiscussionElement(Element element) {
		if (element.hasClass("result-discussion")) {
			if (element.childrenSize() != 1) {
				System.err.println(
					"FIXME: Function return comment structure unknown (result-discussion)."
				);
				return false;
			}
			if (!element.child(0).hasClass("para-returns")) {
				System.err.println(
					"FIXME: Function return comment structure unknown (para-returns)."
				);
				return false;
			}
			if (!element.child(0).child(0).hasClass("word-returns")) {
				System.err.println(
					"FIXME: Function return comment structure unknown (word-returns)."
				);
				return false;
			}
			return true;
		}

		return false;
	}

	private static String getReturnDoc(String functionName, Element element) {
		return Optional.ofNullable(element.nextElementSibling())
			.map(el -> el.select(".doc > .dox > .result-discussion"))
			.filter(elements -> !elements.isEmpty())
			.map(
				elements -> {
					if (elements.size() != 1) throw new IllegalStateException();
					return elements.get(0);
				}
			).filter(el -> isResultDiscussionElement(el)).map(
				el -> {
					final String result = el.child(0).text().substring(
						el.child(0).child(0).text().length()
					);

					final String info = Optional.ofNullable(
						ArdourLuaScraper.FUNCTION_DOC_PROPERTIES.getProperty(functionName + ":return")
					).orElse("");
					return result.isEmpty() ? info : result + " " + info;
				}
			).orElse("");
	}

	private static Map<Integer, Map.Entry<String, String>> getParamsInfo(Element element) {
		final Map<Integer, Map.Entry<String, String>> result = new TreeMap<>();
		Optional.ofNullable(element.nextElementSibling())
			.map(el -> el.select(".doc > .dox > dl"))
			.ifPresent(
				elements -> elements.stream().flatMap(el -> el.children().stream()).forEach(
					el -> el.classNames().forEach(
						className -> {
							if (className.startsWith("param-name-index-")) {
								try {
									final int idx = Integer.valueOf(
										className.substring("param-name-index-".length())
									);
									result.put(
										idx,
										new AbstractMap.SimpleEntry<>(
											adjustParamName(el.text()), null
										)
									);
								} catch (NumberFormatException e) {
									System.err.println("Failed to get param index: " + className);
									System.err.println("\tText: " + el.text());
								}
							} else if (className.startsWith("param-descr-index-")) {
								try {
									final int idx = Integer. valueOf(
										className.substring("param-descr-index-".length())
									);
									result.get(idx).setValue(el.text());
								} catch (NumberFormatException e) {
									System.err.println("Failed to get param index: " + className);
									System.err.println("\tText: " + el.text());
								}
							}
						}
					)
				)
			);
		return result;
	}

	private static String adjustParamName(String name) {
		if ("end".equals(name)) return "end_";
		return name;
	}

	private static boolean isMemberDefElement(Element element) {
		return element.childrenSize() > 1 &&
			element.child(0).hasClass("def") &&
			element.child(1).hasClass("decl");
	}

	private static boolean isFunctionDefElement(Element element) {
		return isMemberDefElement(element) && Optional.of(element.child(1))
			.filter(el -> el.childrenSize() > 0)
			.map(el -> el.child(0))
			.map(el -> el.hasClass("functionname"))
			.orElse(false);
	}

	private static String idToLuaType(String id) {
		if (id.isEmpty()) throw new IllegalStateException();
		return id.substring(id.lastIndexOf(" ") + 1)
			.replace("::", ".")
			.replace(':', '.');
	}

	private static List<LuaField> getParams(String functionName, Element element) {
		final Map<Integer, Map.Entry<String, String>> paramInfoMap = getParamsInfo(element);

		final List<String> paramTypes = element.child(1)
			.select(".functionargs > a,.functionargs > span").stream()
			.map(LuaClass::getParamType)
			// In some rare cases arguments are not in separate spans, so we need to handle it.
			.flatMap(type -> Stream.of(type.split(",")).map(String::trim))
			.collect(Collectors.toList());

		return IntStream.range(0, paramTypes.size()).mapToObj(
			idx -> {
				String paramName = null;
				String paramDoc = null;
				final Map.Entry<String, String> entry = paramInfoMap.get(idx);
				if (entry != null) {
					paramName = entry.getKey();
					paramDoc = entry.getValue();
				}

				final String info = ArdourLuaScraper.FUNCTION_DOC_PROPERTIES.getProperty(
					functionName + ":" + idx
				);
				if (info != null) {
					if (paramName == null) {
						paramName = info.substring(0, info.indexOf(':'));
					}
					paramDoc = (paramDoc == null ? "" : paramDoc + " ") +
						info.substring(info.indexOf(':') + 1);
				}

				return new LuaField(paramName, paramTypes.get(idx), paramDoc);
			}
		).collect(Collectors.toList());
	}

	private static boolean isConstructor(Element defElement) {
		return isNilPointerConstructor(defElement) || "\u2102".equals(defElement.text());
	}

	private static boolean isNilPointerConstructor(Element defElement) {
		return "Nil Pointer Constructor".equals(defElement.attributes().get("title")) ||
			"\u2135".equals(defElement.text());
	}

	/**
	 * @return {@code null} if LUA constructor.
	 */
	private static String getParamType(Element defElement) {
		if ("a".equals(defElement.tagName())) {
			final String href = defElement.attributes().get("href");
			if (!href.isEmpty() && href.startsWith("#")) {
				return idToLuaType(href.substring(1));
			}
		}
		return defElement.text();
	}
}
