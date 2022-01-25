package com.grigoriliev.emmylua.ardour;

import java.util.List;

public record LuaEnum (String type, List<String> enumVars, LuaClass parent) {
	public LuaEnum(String type, List<String> enumVars) {
		this(type, enumVars, null);
	}

	public LuaEnum withParent(LuaClass parent) {
		return new LuaEnum(type(), enumVars(), parent);
	}
}
