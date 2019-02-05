/*
 * Copyright (c) 2013, 2018 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.methods;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.object.DynamicObject;

import org.truffleruby.collections.ConcurrentOperations;
import org.truffleruby.core.kernel.TraceManager;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.RubyArguments;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Define a method from a module body (module/class/class << self ... end).
 */
@GenerateWrapper
public class ModuleBodyDefinitionNode extends RubyNode {

    private final String name;
    private final SharedMethodInfo sharedMethodInfo;
    private final RootCallTarget callTarget;
    private final boolean captureBlock;
    private final boolean dynamicLexicalScope;
    private final Map<DynamicObject, LexicalScope> lexicalScopes;

    public ModuleBodyDefinitionNode(String name, SharedMethodInfo sharedMethodInfo,
            RootCallTarget callTarget, boolean captureBlock, boolean dynamicLexicalScope) {
        this.name = name;
        this.sharedMethodInfo = sharedMethodInfo;
        this.callTarget = callTarget;
        this.captureBlock = captureBlock;
        this.dynamicLexicalScope = dynamicLexicalScope;
        this.lexicalScopes = dynamicLexicalScope ? new ConcurrentHashMap<>() : null;
    }

    public ModuleBodyDefinitionNode(ModuleBodyDefinitionNode node) {
        this(node.name, node.sharedMethodInfo, node.callTarget, node.captureBlock, node.dynamicLexicalScope);
    }

    @Override
    public WrapperNode createWrapper(ProbeNode probe) {
        return new ModuleBodyDefinitionNodeWrapper(this, this, probe);
    }

    public InternalMethod createMethod(VirtualFrame frame, LexicalScope staticLexicalScope, DynamicObject module) {
        final DynamicObject capturedBlock;

        if (captureBlock) {
            capturedBlock = RubyArguments.getBlock(frame);
        } else {
            capturedBlock = null;
        }

        final LexicalScope parentLexicalScope = RubyArguments.getMethod(frame).getLexicalScope();
        final LexicalScope lexicalScope = prepareLexicalScope(staticLexicalScope, parentLexicalScope, module);
        final DeclarationContext declarationContext = new DeclarationContext(
                Visibility.PUBLIC,
                new DeclarationContext.FixedDefaultDefinee(module),
                RubyArguments.getDeclarationContext(frame).getRefinements());
        return new InternalMethod(getContext(), sharedMethodInfo, lexicalScope, declarationContext, name, module, Visibility.PUBLIC, false, null, callTarget, capturedBlock);
    }

    @TruffleBoundary
    private LexicalScope prepareLexicalScope(LexicalScope staticLexicalScope, LexicalScope parentLexicalScope, DynamicObject module) {
        staticLexicalScope.unsafeSetLiveModule(module);
        if (!dynamicLexicalScope) {
            return staticLexicalScope;
        } else {
            // Cache the scope per module in case the module body is run multiple times.
            // This allows dynamic constant lookup to cache better.
            return ConcurrentOperations.getOrCompute(lexicalScopes, module,
                    k -> new LexicalScope(parentLexicalScope, module));
        }
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // For the purpose of tracing in the right order
        return nil();
    }


    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == TraceManager.ClassTag.class) {
            return true;
        }
        return super.hasTag(tag);
    }
}
