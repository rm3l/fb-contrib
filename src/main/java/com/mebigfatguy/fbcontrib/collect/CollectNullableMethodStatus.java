/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2017 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.collect;

import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Method;

import com.mebigfatguy.fbcontrib.utils.AnnotationUtils;

import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.NonReportingDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.XMethod;

@CustomUserValue
public class CollectNullableMethodStatus extends BytecodeScanningDetector implements NonReportingDetector {
    private OpcodeStack stack;
    private boolean methodIsNullable;

    /**
     * @param bugReporter
     *            unused
     */
    public CollectNullableMethodStatus(BugReporter bugReporter) {
    }

    @Override
    public void visitClassContext(ClassContext classContext) {
        try {
            stack = new OpcodeStack();
            super.visitClassContext(classContext);
        } finally {
            stack = null;
        }
    }

    @Override
    public void visitCode(Code obj) {

        Method method = getMethod();
        if (AnnotationUtils.methodHasNullableAnnotation(method)) {
            MethodInfo methodInfo = Statistics.getStatistics().getMethodStatistics(getClassName(), method.getName(), method.getSignature());
            methodInfo.setCanReturnNull(true);
            return;
        }
        methodIsNullable = false;
        stack.resetForMethodEntry(this);
        super.visitCode(obj);
    }

    @Override
    public void sawOpcode(int seen) {
        if (methodIsNullable) {
            return;
        }

        try {
            switch (seen) {
                case ARETURN: {
                    if (!methodIsNullable) {
                        OpcodeStack.Item itm = stack.getStackItem(0);
                        if (itm.isNull()) {
                            Method thisMethod = getMethod();
                            MethodInfo mi = Statistics.getStatistics().getMethodStatistics(getClassName(), thisMethod.getName(), thisMethod.getSignature());
                            if (mi != null) {
                                mi.setCanReturnNull(true);
                            }
                            methodIsNullable = true;
                        } else {
                            XMethod xm = itm.getReturnValueOf();
                            if (xm != null) {
                                MethodInfo mi = Statistics.getStatistics().getMethodStatistics(xm.getClassName().replace('.', '/'), xm.getName(),
                                        xm.getSignature());
                                if ((mi != null) && mi.getCanReturnNull()) {
                                    Method thisMethod = getMethod();
                                    mi = Statistics.getStatistics().getMethodStatistics(getClassName(), thisMethod.getName(), thisMethod.getSignature());
                                    if (mi != null) {
                                        mi.setCanReturnNull(true);
                                    }
                                    methodIsNullable = true;
                                }
                            }
                        }
                    }
                    break;
                }
            }
        } finally {
            stack.sawOpcode(this, seen);
        }
    }
}