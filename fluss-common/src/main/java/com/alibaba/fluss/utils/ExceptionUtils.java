/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//
// The function "stringifyException" is based on source code from the Hadoop Project
// (http://hadoop.apache.org/),
// licensed by the Apache Software Foundation (ASF) under the Apache License, Version 2.0.
// See the NOTICE file distributed with this work for additional information regarding copyright
// ownership.
//

package com.alibaba.fluss.utils;

import com.alibaba.fluss.annotation.Internal;

import javax.annotation.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.alibaba.fluss.utils.Preconditions.checkNotNull;

/* This file is based on source code of Apache Flink Project (https://flink.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/** A collection of utility functions for dealing with exceptions and exception workflows. */
@Internal
public final class ExceptionUtils {

    /** The stringified representation of a null exception reference. */
    public static final String STRINGIFIED_NULL_EXCEPTION = "(null)";

    /**
     * Makes a string representation of the exception's stack trace, or "(null)", if the exception
     * is null.
     *
     * <p>This method makes a best effort and never fails.
     *
     * @param e The exception to stringify.
     * @return A string with exception name and call stack.
     */
    public static String stringifyException(final Throwable e) {
        if (e == null) {
            return STRINGIFIED_NULL_EXCEPTION;
        }

        try {
            StringWriter stm = new StringWriter();
            PrintWriter wrt = new PrintWriter(stm);
            e.printStackTrace(wrt);
            wrt.close();
            return stm.toString();
        } catch (Throwable t) {
            return e.getClass().getName() + " (error while printing stack trace)";
        }
    }

    /**
     * Unpacks an {@link CompletionException} and returns its cause. Otherwise the given Throwable
     * is returned.
     *
     * @param throwable to unpack if it is an CompletionException
     * @return Cause of CompletionException or given Throwable
     */
    public static Throwable stripCompletionException(Throwable throwable) {
        return stripException(throwable, CompletionException.class);
    }

    /**
     * Checks whether the given exception indicates a situation that may leave the JVM in a
     * corrupted state, meaning a state where continued normal operation can only be guaranteed via
     * clean process restart.
     *
     * <p>Currently considered fatal exceptions are Virtual Machine errors indicating that the JVM
     * is corrupted, like {@link InternalError}, {@link UnknownError}, and {@link
     * java.util.zip.ZipError} (a special case of InternalError). The {@link ThreadDeath} exception
     * is also treated as a fatal error, because when a thread is forcefully stopped, there is a
     * high chance that parts of the system are in an inconsistent state.
     *
     * @param t The exception to check.
     * @return True, if the exception is considered fatal to the JVM, false otherwise.
     */
    public static boolean isJvmFatalError(Throwable t) {
        return (t instanceof InternalError)
                || (t instanceof UnknownError)
                || (t instanceof ThreadDeath);
    }

    /**
     * Checks whether the given exception indicates a situation that may leave the JVM in a
     * corrupted state, or an out-of-memory error.
     *
     * <p>See {@link ExceptionUtils#isJvmFatalError(Throwable)} for a list of fatal JVM errors. This
     * method additionally classifies the {@link OutOfMemoryError} as fatal, because it may occur in
     * any thread (not the one that allocated the majority of the memory) and thus is often not
     * recoverable by destroying the particular thread that threw the exception.
     *
     * @param t The exception to check.
     * @return True, if the exception is fatal to the JVM or and OutOfMemoryError, false otherwise.
     */
    public static boolean isJvmFatalOrOutOfMemoryError(Throwable t) {
        return isJvmFatalError(t) || t instanceof OutOfMemoryError;
    }

    /**
     * Tries to enrich OutOfMemoryErrors being part of the passed root Throwable's cause tree.
     *
     * <p>This method improves error messages for direct and metaspace {@link OutOfMemoryError}. It
     * adds description about the possible causes and ways of resolution.
     *
     * @param root The Throwable of which the cause tree shall be traversed.
     * @param jvmMetaspaceOomNewErrorMessage The message being used for JVM metaspace-related
     *     OutOfMemoryErrors. Passing <code>null</code> will disable handling this class of error.
     * @param jvmDirectOomNewErrorMessage The message being used for direct memory-related
     *     OutOfMemoryErrors. Passing <code>null</code> will disable handling this class of error.
     * @param jvmHeapSpaceOomNewErrorMessage The message being used for Heap space-related
     *     OutOfMemoryErrors. Passing <code>null</code> will disable handling this class of error.
     */
    public static void tryEnrichOutOfMemoryError(
            @Nullable Throwable root,
            @Nullable String jvmMetaspaceOomNewErrorMessage,
            @Nullable String jvmDirectOomNewErrorMessage,
            @Nullable String jvmHeapSpaceOomNewErrorMessage) {
        updateDetailMessage(
                root,
                t -> {
                    if (isMetaspaceOutOfMemoryError(t)) {
                        return jvmMetaspaceOomNewErrorMessage;
                    } else if (isDirectOutOfMemoryError(t)) {
                        return jvmDirectOomNewErrorMessage;
                    } else if (isHeapSpaceOutOfMemoryError(t)) {
                        return jvmHeapSpaceOomNewErrorMessage;
                    }

                    return null;
                });
    }

    /**
     * Updates error messages of Throwables appearing in the cause tree of the passed root
     * Throwable. The passed Function is applied on each Throwable of the cause tree. Returning a
     * String will cause the detailMessage of the corresponding Throwable to be updated. Returning
     * <code>null</code>, instead, won't trigger any detailMessage update on that Throwable.
     *
     * @param root The Throwable whose cause tree shall be traversed.
     * @param throwableToMessage The Function based on which the new messages are generated. The
     *     function implementation should return the new message. Returning <code>null</code>, in
     *     contrast, will result in not updating the message for the corresponding Throwable.
     */
    public static void updateDetailMessage(
            @Nullable Throwable root, @Nullable Function<Throwable, String> throwableToMessage) {
        if (throwableToMessage == null) {
            return;
        }

        Throwable it = root;
        while (it != null) {
            String newMessage = throwableToMessage.apply(it);
            if (newMessage != null) {
                updateDetailMessageOfThrowable(it, newMessage);
            }

            it = it.getCause();
        }
    }

    private static void updateDetailMessageOfThrowable(
            Throwable throwable, String newDetailMessage) {
        Field field;
        try {
            field = Throwable.class.getDeclaredField("detailMessage");
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    "The JDK Throwable contains a detailMessage member. The Throwable class provided on the classpath does not which is why this exception appears.",
                    e);
        }

        field.setAccessible(true);
        try {
            field.set(throwable, newDetailMessage);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "The JDK Throwable contains a private detailMessage member that should be accessible through reflection. This is not the case for the Throwable class provided on the classpath.",
                    e);
        }
    }

    /**
     * Checks whether the given exception indicates a JVM metaspace out-of-memory error.
     *
     * @param t The exception to check.
     * @return True, if the exception is the metaspace {@link OutOfMemoryError}, false otherwise.
     */
    public static boolean isMetaspaceOutOfMemoryError(@Nullable Throwable t) {
        return isOutOfMemoryErrorWithMessageContaining(t, "Metaspace");
    }

    /**
     * Checks whether the given exception indicates a JVM direct out-of-memory error.
     *
     * @param t The exception to check.
     * @return True, if the exception is the direct {@link OutOfMemoryError}, false otherwise.
     */
    public static boolean isDirectOutOfMemoryError(@Nullable Throwable t) {
        return isOutOfMemoryErrorWithMessageContaining(t, "Direct buffer memory");
    }

    public static boolean isHeapSpaceOutOfMemoryError(@Nullable Throwable t) {
        return isOutOfMemoryErrorWithMessageContaining(t, "Java heap space");
    }

    private static boolean isOutOfMemoryErrorWithMessageContaining(
            @Nullable Throwable t, String infix) {
        // the exact matching of the class is checked to avoid matching any custom subclasses of
        // OutOfMemoryError
        // as we are interested in the original exceptions, generated by JVM.
        return isOutOfMemoryError(t)
                && t.getMessage() != null
                && t.getMessage().toLowerCase(Locale.ROOT).contains(infix.toLowerCase(Locale.ROOT));
    }

    private static boolean isOutOfMemoryError(@Nullable Throwable t) {
        return t != null && t.getClass() == OutOfMemoryError.class;
    }

    /**
     * Throws the given {@code Throwable} in scenarios where the signatures do allow to throw a
     * Exception. Errors and Exceptions are thrown directly, other "exotic" subclasses of Throwable
     * are wrapped in an Exception.
     *
     * @param t The throwable to be thrown.
     * @param parentMessage The message for the parent Exception, if one is needed.
     */
    public static void rethrowException(Throwable t, String parentMessage) throws Exception {
        if (t instanceof Error) {
            throw (Error) t;
        } else if (t instanceof Exception) {
            throw (Exception) t;
        } else {
            throw new Exception(parentMessage, t);
        }
    }

    /** Converts a {@link Throwable} to an {@link Exception}. */
    public static Exception toException(Throwable t) {
        if (t instanceof Exception) {
            return (Exception) t;
        } else {
            return new Exception(t);
        }
    }

    /**
     * Tries to throw the given exception if not null.
     *
     * @param e exception to throw if not null.
     * @throws Exception
     */
    public static void tryRethrowException(@Nullable Exception e) throws Exception {
        if (e != null) {
            throw e;
        }
    }

    /**
     * Rethrows the given {@code Throwable}, if it represents an error that is fatal to the JVM. See
     * {@link ExceptionUtils#isJvmFatalError(Throwable)} for a definition of fatal errors.
     *
     * @param t The Throwable to check and rethrow.
     */
    public static void rethrowIfFatalError(Throwable t) {
        if (isJvmFatalError(t)) {
            throw (Error) t;
        }
    }

    /**
     * Rethrows the given {@code Throwable}, if it represents an error that is fatal to the JVM or
     * an out-of-memory error. See {@link ExceptionUtils#isJvmFatalError(Throwable)} for a
     * definition of fatal errors.
     *
     * @param t The Throwable to check and rethrow.
     */
    public static void rethrowIfFatalErrorOrOOM(Throwable t) {
        if (isJvmFatalError(t) || t instanceof OutOfMemoryError) {
            throw (Error) t;
        }
    }

    /**
     * Throws the given {@code Throwable} in scenarios where the signatures do not allow you to
     * throw an arbitrary Throwable. Errors and RuntimeExceptions are thrown directly, other
     * exceptions are packed into runtime exceptions
     *
     * @param t The throwable to be thrown.
     */
    public static void rethrow(Throwable t) {
        if (t instanceof Error) {
            throw (Error) t;
        } else if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        } else {
            throw new RuntimeException(t);
        }
    }

    /**
     * Adds a new exception as a {@link Throwable#addSuppressed(Throwable) suppressed exception} to
     * a prior exception, or returns the new exception, if no prior exception exists.
     *
     * <pre>{@code
     * public void closeAllThings() throws Exception {
     *     Exception ex = null;
     *     try {
     *         component.shutdown();
     *     } catch (Exception e) {
     *         ex = firstOrSuppressed(e, ex);
     *     }
     *     try {
     *         anotherComponent.stop();
     *     } catch (Exception e) {
     *         ex = firstOrSuppressed(e, ex);
     *     }
     *     try {
     *         lastComponent.shutdown();
     *     } catch (Exception e) {
     *         ex = firstOrSuppressed(e, ex);
     *     }
     *
     *     if (ex != null) {
     *         throw ex;
     *     }
     * }
     * }</pre>
     *
     * @param newException The newly occurred exception
     * @param previous The previously occurred exception, possibly null.
     * @return The new exception, if no previous exception exists, or the previous exception with
     *     the new exception in the list of suppressed exceptions.
     */
    public static <T extends Throwable> T firstOrSuppressed(T newException, @Nullable T previous) {
        checkNotNull(newException, "newException");

        if (previous == null || previous == newException) {
            return newException;
        } else {
            previous.addSuppressed(newException);
            return previous;
        }
    }

    /**
     * Checks whether a throwable chain contains a specific type of exception and returns it.
     *
     * @param throwable the throwable chain to check.
     * @param searchType the type of exception to search for in the chain.
     * @return Optional throwable of the requested type if available, otherwise empty
     */
    public static <T extends Throwable> Optional<T> findThrowable(
            Throwable throwable, Class<T> searchType) {
        if (throwable == null || searchType == null) {
            return Optional.empty();
        }

        Throwable t = throwable;
        while (t != null) {
            if (searchType.isAssignableFrom(t.getClass())) {
                return Optional.of(searchType.cast(t));
            } else {
                t = t.getCause();
            }
        }

        return Optional.empty();
    }

    /**
     * Checks whether a throwable chain contains an exception matching a predicate and returns it.
     *
     * @param throwable the throwable chain to check.
     * @param predicate the predicate of the exception to search for in the chain.
     * @return Optional throwable of the requested type if available, otherwise empty
     */
    public static Optional<Throwable> findThrowable(
            Throwable throwable, Predicate<Throwable> predicate) {
        if (throwable == null || predicate == null) {
            return Optional.empty();
        }

        Throwable t = throwable;
        while (t != null) {
            if (predicate.test(t)) {
                return Optional.of(t);
            } else {
                t = t.getCause();
            }
        }

        return Optional.empty();
    }

    /**
     * Checks whether a throwable chain contains a specific error message and returns the
     * corresponding throwable.
     *
     * @param throwable the throwable chain to check.
     * @param searchMessage the error message to search for in the chain.
     * @return Optional throwable containing the search message if available, otherwise empty
     */
    public static Optional<Throwable> findThrowableWithMessage(
            Throwable throwable, String searchMessage) {
        if (throwable == null || searchMessage == null) {
            return Optional.empty();
        }

        Throwable t = throwable;
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains(searchMessage)) {
                return Optional.of(t);
            } else {
                t = t.getCause();
            }
        }

        return Optional.empty();
    }

    /**
     * Unpacks an {@link ExecutionException} and returns its cause. Otherwise the given Throwable is
     * returned.
     *
     * @param throwable to unpack if it is an ExecutionException
     * @return Cause of ExecutionException or given Throwable
     */
    public static Throwable stripExecutionException(Throwable throwable) {
        return stripException(throwable, ExecutionException.class);
    }

    /**
     * Unpacks an specified exception and returns its cause. Otherwise the given {@link Throwable}
     * is returned.
     *
     * @param throwableToStrip to strip
     * @param typeToStrip type to strip
     * @return Unpacked cause or given Throwable if not packed
     */
    public static Throwable stripException(
            Throwable throwableToStrip, Class<? extends Throwable> typeToStrip) {
        while (typeToStrip.isAssignableFrom(throwableToStrip.getClass())
                && throwableToStrip.getCause() != null) {
            throwableToStrip = throwableToStrip.getCause();
        }

        return throwableToStrip;
    }

    // ------------------------------------------------------------------------

    /** Private constructor to prevent instantiation. */
    private ExceptionUtils() {}
}
