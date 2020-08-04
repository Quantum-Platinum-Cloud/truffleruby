/*
 * Copyright (c) 2013, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.fiber;

import org.truffleruby.Layouts;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreMethodNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.cast.SingleValueCastNode;
import org.truffleruby.core.cast.SingleValueCastNodeGen;
import org.truffleruby.core.fiber.FiberNodesFactory.FiberTransferNodeFactory;
import org.truffleruby.core.thread.GetCurrentRubyThreadNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.methods.UnsupportedOperationBehavior;
import org.truffleruby.language.objects.AllocateHelperNode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

@CoreModule(value = "Fiber", isClass = true)
public abstract class FiberNodes {

    public abstract static class FiberTransferNode extends CoreMethodArrayArgumentsNode {

        @Child private SingleValueCastNode singleValueCastNode;

        public Object singleValue(Object[] args) {
            if (singleValueCastNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                singleValueCastNode = insert(SingleValueCastNodeGen.create());
            }
            return singleValueCastNode.executeSingleValue(args);
        }

        public abstract Object executeTransferControlTo(DynamicObject currentThread, RubyFiber currentFiber,
                RubyFiber fiber, FiberOperation operation, Object[] args);

        @Specialization
        protected Object transfer(
                DynamicObject currentThread,
                RubyFiber currentFiber,
                RubyFiber fiber,
                FiberOperation operation,
                Object[] args,
                @Cached BranchProfile errorProfile) {

            if (!fiber.alive) {
                errorProfile.enter();
                throw new RaiseException(getContext(), coreExceptions().deadFiberCalledError(this));
            }

            if (fiber.rubyThread != currentThread) {
                errorProfile.enter();
                throw new RaiseException(
                        getContext(),
                        coreExceptions().fiberError("fiber called across threads", this));
            }

            final FiberManager fiberManager = Layouts.THREAD.getFiberManager(currentThread);
            return singleValue(fiberManager.transferControlTo(currentFiber, fiber, operation, args));
        }

    }

    @CoreMethod(names = { "__allocate__", "__layout_allocate__" }, constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected RubyFiber allocate(DynamicObject rubyClass,
                @Cached AllocateHelperNode helperNode) {
            final DynamicObject thread = getContext().getThreadManager().getCurrentThread();
            final Shape shape = helperNode.getCachedShape(rubyClass);
            final RubyFiber fiber = Layouts.THREAD.getFiberManager(thread).createFiber(getContext(), thread, shape);
            helperNode.trace(fiber, this);
            return fiber;
        }

    }

    @CoreMethod(
            names = "initialize",
            needsBlock = true,
            unsupportedOperationBehavior = UnsupportedOperationBehavior.ARGUMENT_ERROR)
    public abstract static class InitializeNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object initialize(RubyFiber fiber, DynamicObject block) {
            final DynamicObject thread = getContext().getThreadManager().getCurrentThread();
            Layouts.THREAD.getFiberManager(thread).initialize(fiber, block, this);
            return nil;
        }

    }

    @CoreMethod(names = "transfer", rest = true)
    public abstract static class TransferNode extends CoreMethodArrayArgumentsNode {

        @Child private FiberTransferNode fiberTransferNode = FiberTransferNodeFactory.create(null);

        @Specialization
        protected Object resume(RubyFiber fiber, Object[] args,
                @Cached GetCurrentRubyThreadNode getCurrentRubyThreadNode,
                @Cached ConditionProfile sameFiberProfile) {

            fiber.transferred = true;

            final DynamicObject currentThread = getCurrentRubyThreadNode.execute();
            final FiberManager fiberManager = Layouts.THREAD.getFiberManager(currentThread);
            final RubyFiber currentFiber = fiberManager.getCurrentFiber();

            if (sameFiberProfile.profile(currentFiber == fiber)) {
                // A Fiber can transfer to itself
                return fiberTransferNode.singleValue(args);
            }

            return fiberTransferNode
                    .executeTransferControlTo(currentThread, currentFiber, fiber, FiberOperation.TRANSFER, args);
        }

    }

    @CoreMethod(names = "resume", rest = true)
    public abstract static class ResumeNode extends CoreMethodArrayArgumentsNode {

        @Child private FiberTransferNode fiberTransferNode = FiberTransferNodeFactory.create(null);

        @Specialization
        protected Object resume(RubyFiber fiber, Object[] args,
                @Cached GetCurrentRubyThreadNode getCurrentRubyThreadNode,
                @Cached ConditionProfile doubleResumeProfile,
                @Cached ConditionProfile transferredProfile) {

            final RubyFiber parentFiber = fiber.lastResumedByFiber;
            final FiberManager fiberToResumeManager = Layouts.THREAD
                    .getFiberManager(fiber.rubyThread);

            if (doubleResumeProfile.profile(parentFiber != null || fiber == fiberToResumeManager.getRootFiber())) {
                throw new RaiseException(getContext(), coreExceptions().fiberError("double resume", this));
            }

            if (transferredProfile.profile(fiber.transferred)) {
                throw new RaiseException(
                        getContext(),
                        coreExceptions().fiberError("cannot resume transferred Fiber", this));
            }

            final DynamicObject currentThread = getCurrentRubyThreadNode.execute();
            final FiberManager fiberManager = Layouts.THREAD.getFiberManager(currentThread);
            final RubyFiber currentFiber = fiberManager.getCurrentFiber();

            return fiberTransferNode
                    .executeTransferControlTo(currentThread, currentFiber, fiber, FiberOperation.RESUME, args);
        }

    }

    @CoreMethod(names = "yield", onSingleton = true, rest = true)
    public abstract static class YieldNode extends CoreMethodArrayArgumentsNode {

        @Child private FiberTransferNode fiberTransferNode = FiberTransferNodeFactory.create(null);

        @Specialization
        protected Object yield(Object[] args,
                @Cached GetCurrentRubyThreadNode getCurrentRubyThreadNode,
                @Cached BranchProfile errorProfile) {

            final DynamicObject currentThread = getCurrentRubyThreadNode.execute();
            final FiberManager fiberManager = Layouts.THREAD.getFiberManager(currentThread);
            final RubyFiber currentFiber = fiberManager.getCurrentFiber();

            final RubyFiber fiberYieldedTo = fiberManager.getReturnFiber(currentFiber, this, errorProfile);

            return fiberTransferNode.executeTransferControlTo(
                    currentThread,
                    currentFiber,
                    fiberYieldedTo,
                    FiberOperation.YIELD,
                    args);
        }

    }

    @CoreMethod(names = "alive?")
    public abstract static class AliveNode extends UnaryCoreMethodNode {

        @Specialization
        protected boolean alive(RubyFiber fiber) {
            return fiber.alive;
        }

    }

    @CoreMethod(names = "current", onSingleton = true)
    public abstract static class CurrentNode extends CoreMethodNode {

        @Specialization
        protected RubyFiber current(
                @Cached GetCurrentRubyThreadNode getCurrentRubyThreadNode) {
            final DynamicObject currentThread = getCurrentRubyThreadNode.execute();
            return Layouts.THREAD.getFiberManager(currentThread).getCurrentFiber();
        }

    }

    @Primitive(name = "fiber_get_catch_tags")
    public static abstract class FiberGetCatchTagsNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected RubyArray getCatchTags(
                @Cached GetCurrentRubyThreadNode getCurrentRubyThreadNode) {
            final DynamicObject currentThread = getCurrentRubyThreadNode.execute();
            final RubyFiber currentFiber = Layouts.THREAD.getFiberManager(currentThread).getCurrentFiber();
            return currentFiber.catchTags;
        }
    }

}
