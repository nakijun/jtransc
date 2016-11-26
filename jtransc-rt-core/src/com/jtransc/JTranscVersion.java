package com.jtransc;

import com.jtransc.annotation.JTranscMethodBody;
import com.jtransc.annotation.haxe.HaxeMethodBody;

public class JTranscVersion {
	static private final String version = "0.5.0-ALPHA2";

	static public String getVersion() {
		return version;
	}

	@HaxeMethodBody("return N.str('haxe');")
	@JTranscMethodBody(target = "js", value = "return N.str('js');")
	static public String getRuntime() {
		return "java";
	}
}
