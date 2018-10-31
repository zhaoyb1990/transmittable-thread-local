package com.alibaba.ttl.threadpool.agent.internal.transformlet.impl;

import com.alibaba.ttl.threadpool.agent.internal.logging.Logger;
import com.alibaba.ttl.threadpool.agent.internal.transformlet.JavassistTransformlet;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.alibaba.ttl.threadpool.agent.internal.transformlet.impl.Utils.*;

/**
 * TTL {@link JavassistTransformlet} for {@link java.util.concurrent.Executor}.
 *
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @author wuwen5 (wuwen.55 at aliyun dot com)
 * @see java.util.concurrent.Executor
 * @see java.util.concurrent.ExecutorService
 * @see java.util.concurrent.ThreadPoolExecutor
 * @see java.util.concurrent.ScheduledThreadPoolExecutor
 * @see java.util.concurrent.Executors
 * @since 2.5.1
 */
public class TtlExecutorTransformlet implements JavassistTransformlet {
    private static final Logger logger = Logger.getLogger(TtlExecutorTransformlet.class);

    private static final String THREAD_POOL_EXECUTOR_CLASS_NAME = "java.util.concurrent.ThreadPoolExecutor";
    private static final String RUNNABLE_CLASS_NAME = "java.lang.Runnable";

    private static Set<String> EXECUTOR_CLASS_NAMES = new HashSet<String>();
    private static final Map<String, String> PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS = new HashMap<String, String>();

    static {
        EXECUTOR_CLASS_NAMES.add(THREAD_POOL_EXECUTOR_CLASS_NAME);
        EXECUTOR_CLASS_NAMES.add("java.util.concurrent.ScheduledThreadPoolExecutor");

        PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS.put(RUNNABLE_CLASS_NAME, "com.alibaba.ttl.TtlRunnable");
        PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS.put("java.util.concurrent.Callable", "com.alibaba.ttl.TtlCallable");
    }

    @Override
    public byte[] doTransform(String className, byte[] classFileBuffer, ClassLoader loader) throws IOException, NotFoundException, CannotCompileException {
        if (EXECUTOR_CLASS_NAMES.contains(className)) {
            final CtClass clazz = getCtClass(classFileBuffer, loader);
            for (CtMethod method : clazz.getDeclaredMethods()) {
                updateMethodOfExecutorClass(clazz, method);
            }

            if (THREAD_POOL_EXECUTOR_CLASS_NAME.equals(className)) {
                updateThreadPoolExecutorClassDisableInheritable(clazz);
            }

            return clazz.toBytecode();
        }
        return null;
    }

    private void updateMethodOfExecutorClass(final CtClass clazz, final CtMethod method) throws NotFoundException, CannotCompileException {
        if (method.getDeclaringClass() != clazz) return;

        final int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) return;

        CtClass[] parameterTypes = method.getParameterTypes();
        StringBuilder insertCode = new StringBuilder();
        for (int i = 0; i < parameterTypes.length; i++) {
            final String paramTypeName = parameterTypes[i].getName();
            if (PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS.containsKey(paramTypeName)) {
                String code = String.format("$%d = %s.get($%d, false, true);", i + 1, PARAM_TYPE_NAME_TO_DECORATE_METHOD_CLASS.get(paramTypeName), i + 1);
                logger.info("insert code before method " + signatureOfMethod(method) + " of class " + method.getDeclaringClass().getName() + ": " + code);
                insertCode.append(code);
            }
        }
        if (insertCode.length() > 0) method.insertBefore(insertCode.toString());
    }

    private void updateThreadPoolExecutorClassDisableInheritable(final CtClass clazz) throws NotFoundException, CannotCompileException {
        // find method java.util.concurrent.ThreadPoolExecutor.addWorker(Runnable, boolean)
        CtMethod addWorkerMethod;
        try {
            addWorkerMethod = clazz.getDeclaredMethod("addWorker");
            // valid the addWorker method
            final CtClass[] parameterTypes = addWorkerMethod.getParameterTypes();
            if (parameterTypes.length != 2 || !parameterTypes[0].getName().equals(RUNNABLE_CLASS_NAME) || !CtClass.booleanType.equals(parameterTypes[1])) {
                throw new IllegalStateException("Found the wrong addWorker method " + signatureOfMethod(addWorkerMethod) + " of class " + clazz.getName());
            }
        } catch (NotFoundException e) {
            // compatible for java 6
            addWorkerMethod = clazz.getDeclaredMethod("addThread");
            // valid the addThread method
            final CtClass[] parameterTypes = addWorkerMethod.getParameterTypes();
            if (parameterTypes.length != 1 || !parameterTypes[0].getName().equals(RUNNABLE_CLASS_NAME)) {
                throw new IllegalStateException("Found the wrong addThread method " + signatureOfMethod(addWorkerMethod) + " of class " + clazz.getName());
            }
        }

        final String beforeCode = "Object backup = com.alibaba.ttl.TransmittableThreadLocal.Transmitter.clear();";
        final String finallyCode = "com.alibaba.ttl.TransmittableThreadLocal.Transmitter.restore(backup);";

        doTryFinallyForMethod(addWorkerMethod, beforeCode, finallyCode);
    }
}
