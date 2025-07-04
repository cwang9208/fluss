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

package com.alibaba.fluss.utils.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/** Test for {@link com.alibaba.fluss.utils.function.FunctionUtils}. */
class FunctionUtilsTest {

    @Test
    void testUncheckedFunction() {
        // test a uncheckedFunction which will throw exception in apply method
        String exceptionMsg = "this is exception message.";
        Function<String, String> uncheckedFunction =
                FunctionUtils.uncheckedFunction(new TestFunctionWithException());
        assertThatThrownBy(() -> uncheckedFunction.apply(exceptionMsg))
                .isInstanceOf(RuntimeException.class)
                .cause()
                .isInstanceOf(IOException.class)
                .hasMessage(exceptionMsg);
    }

    private static class TestFunctionWithException
            implements FunctionWithException<String, String, IOException> {

        @Override
        public String apply(String value) throws IOException {
            throw new IOException(value);
        }
    }
}
