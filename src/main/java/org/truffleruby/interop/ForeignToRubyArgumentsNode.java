/*
 * Copyright (c) 2017, 2023 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.interop;

import org.truffleruby.language.RubyBaseNode;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;

@GenerateUncached
public abstract class ForeignToRubyArgumentsNode extends RubyBaseNode {

    public static ForeignToRubyArgumentsNode create() {
        return ForeignToRubyArgumentsNodeGen.create();
    }

    public abstract Object[] executeConvert(Object[] args);

    @ExplodeLoop
    @Specialization(
            guards = { "args.length == cachedArgsLength", "cachedArgsLength <= MAX_EXPLODE_SIZE" },
            limit = "getLimit()")
    protected Object[] convertCached(Object[] args,
            @Cached("args.length") int cachedArgsLength,
            @Cached("foreignToRubyNodes(cachedArgsLength)") ForeignToRubyNode[] foreignToRubyNodes) {
        final Object[] convertedArgs = new Object[cachedArgsLength];

        for (int n = 0; n < cachedArgsLength; n++) {
            convertedArgs[n] = foreignToRubyNodes[n].executeConvert(args[n]);
        }

        return convertedArgs;
    }

    @Specialization(replaces = "convertCached")
    protected Object[] convertUncached(Object[] args,
            @Cached ForeignToRubyNode foreignToRubyNode) {
        final Object[] convertedArgs = new Object[args.length];

        for (int n = 0; n < args.length; n++) {
            convertedArgs[n] = foreignToRubyNode.executeConvert(args[n]);
        }

        return convertedArgs;
    }

    protected static ForeignToRubyNode[] foreignToRubyNodes(int size) {
        ForeignToRubyNode[] foreignToRubyNodes = new ForeignToRubyNode[size];
        for (int i = 0; i < foreignToRubyNodes.length; i++) {
            foreignToRubyNodes[i] = ForeignToRubyNode.create();
        }
        return foreignToRubyNodes;
    }

    protected int getLimit() {
        return getLanguage().options.INTEROP_CONVERT_CACHE;
    }

}
