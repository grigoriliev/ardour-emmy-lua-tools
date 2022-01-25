package com.grigoriliev.emmylua.ardour;

import java.util.List;

public record LuaFunction (
	String name, String returnType, List<LuaField> arguments, String doc, String returnDoc
) {
	public boolean isConstructor() { return returnType() == null; }

	@Override public String toString() {
		return returnType() + " " + name();
	}
}
