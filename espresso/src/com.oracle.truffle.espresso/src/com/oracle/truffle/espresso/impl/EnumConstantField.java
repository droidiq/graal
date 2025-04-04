/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.impl;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.espresso.classfile.descriptors.TypeSymbols;
import com.oracle.truffle.espresso.constantpool.RuntimeConstantPool;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

public final class EnumConstantField extends Field {

    @CompilationFinal private int initState;

    public EnumConstantField(ObjectKlass.KlassVersion holder, LinkedField linkedField, RuntimeConstantPool pool) {
        super(holder, linkedField, pool);
        assert TypeSymbols.isReference(linkedField.getType());
    }

    @Override
    public void setObject(StaticObject obj, Object value, boolean forceVolatile) {
        if (initState == 0) {
            // handle field init where value is set to StaticObject.NULL
            CompilerDirectives.transferToInterpreterAndInvalidate();
            assert value instanceof StaticObject && StaticObject.isNull((StaticObject) value);
            super.setObject(obj, value, forceVolatile);
            initState = 1;
        } else if (initState == 1) {
            // only allow setting real enum constant value once
            CompilerDirectives.transferToInterpreterAndInvalidate();
            super.setObject(obj, value, forceVolatile);
            initState = 2;
        }
    }
}
