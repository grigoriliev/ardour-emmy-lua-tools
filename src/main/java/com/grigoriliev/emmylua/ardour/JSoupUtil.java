package com.grigoriliev.emmylua.ardour;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.jsoup.nodes.Element;

public class JSoupUtil {

	public static Optional<Element> findSibling(
		Element element, Predicate<Element> findPredicate, Predicate<Element> stopPredicate
	) {
		return findSibling(element, findPredicate, el -> true, stopPredicate);
	}

	public static Optional<Element> findSibling(
		Element element,
		Predicate<Element> findPredicate,
		Predicate<Element> startPredicate,
		Predicate<Element> stopPredicate
	) {
		return findSiblings(element, findPredicate, startPredicate, stopPredicate).findFirst();
	}

	public static Stream<Element> findSiblings(
		Element element, Predicate<Element> findPredicate, Predicate<Element> stopPredicate
	) {
		return findSiblings(element, findPredicate, el -> true, stopPredicate);
	}

	public static Stream<Element> findSiblings(
		Element element,
		Predicate<Element> findPredicate,
		Predicate<Element> startPredicate,
		Predicate<Element> stopPredicate
	) {
		return Optional.ofNullable(element.parent()).map(Element::children).map(
			siblings -> find(
				IntStream.range(element.elementSiblingIndex() + 1, siblings.size())
					.mapToObj(siblings::get),
				findPredicate, startPredicate, stopPredicate
			)
		).orElse(Stream.empty());
	}

	public static Stream<Element> find(
		Stream<Element> elementStream,
		Predicate<Element> findPredicate,
		Predicate<Element> startPredicate,
		Predicate<Element> stopPredicate
	) {
		final boolean[] startedAry = new boolean[1];
		return elementStream.takeWhile(el -> !stopPredicate.test(el)).filter(
			el -> {
				if (startedAry[0]) {
					return findPredicate.test(el);
				} else if (startPredicate.test(el)) {
					startedAry[0] = true;
					return findPredicate.test(el);
				}
				return false;
			}
		);
	}
}
