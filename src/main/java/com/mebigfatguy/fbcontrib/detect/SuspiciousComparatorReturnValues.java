/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2018 Dave Brosius
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
package com.mebigfatguy.fbcontrib.detect;

import java.util.List;

import org.apache.bcel.Const;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.SignatureUtils;
import com.mebigfatguy.fbcontrib.utils.StopOpcodeParsingException;
import com.mebigfatguy.fbcontrib.utils.ToString;
import com.mebigfatguy.fbcontrib.utils.UnmodifiableList;
import com.mebigfatguy.fbcontrib.utils.Values;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.internalAnnotations.SlashedClassName;

/**
 * looks for class that implement Comparator or Comparable, and whose compare or compareTo methods return constant values only, but that don't represent the
 * three possible choice (a negative number, 0, and a positive number).
 */
@CustomUserValue
public class SuspiciousComparatorReturnValues extends BytecodeScanningDetector {
    private static List<CompareSpec> compareClasses;

    static {
        try {
            compareClasses = UnmodifiableList.create(
            // @formatter:off
					new CompareSpec("java/lang/Comparable", new MethodInfo("compareTo", 1, Values.SIG_PRIMITIVE_INT)),
					new CompareSpec(Values.SLASHED_JAVA_UTIL_COMPARATOR,
							new MethodInfo("compare", 2, Values.SIG_PRIMITIVE_INT))
			// @formatter:on
            );
        } catch (ClassNotFoundException e) {
            // ignore no bug reporter yet
        }
    }

    private OpcodeStack stack;
    private final BugReporter bugReporter;
    private MethodInfo methodInfo;
    private boolean seenNegative;
    private boolean seenPositive;
    private boolean seenZero;
    private boolean seenUnconditionalNonZero;
    private int furthestBranchTarget;
    private Integer sawConstant;

    /**
     * constructs a SCRV detector given the reporter to report bugs on
     *
     * @param bugReporter
     *            the sync of bug reports
     */
    public SuspiciousComparatorReturnValues(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    /**
     * implements the visitor to actually iterate twice over this class, once for compareTo and once for compare.
     *
     * @param classContext
     *            the currently parsed class
     */
    @Override
    public void visitClassContext(ClassContext classContext) {
        try {
            JavaClass cls = classContext.getJavaClass();
            for (CompareSpec entry : compareClasses) {
                if (cls.implementationOf(entry.getCompareClass())) {
                    methodInfo = entry.getMethodInfo();
                    stack = new OpcodeStack();
                    super.visitClassContext(classContext);
                    break;
                }
            }
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
        } finally {
            methodInfo = null;
            stack = null;
        }
    }

    /**
     * implements the visitor to check to see what Const were returned from a comparator. If no Const were returned it can't determine anything, however if only
     * Const were returned, it looks to see if negative positive and zero was returned. It also looks to see if a non zero value is returned unconditionally.
     * While it is possible that later check is ok, it usually means something is wrong.
     *
     * @param obj
     *            the currently parsed code block
     */
    @Override
    public void visitCode(Code obj) {
        if (getMethod().isSynthetic()) {
            return;
        }

        String methodName = getMethodName();
        String methodSig = getMethodSig();
        if (methodName.equals(methodInfo.methodName) && methodSig.endsWith(methodInfo.signatureEnding)
                && (SignatureUtils.getNumParameters(methodSig) == methodInfo.argumentCount)) {
            stack.resetForMethodEntry(this);
            seenNegative = false;
            seenPositive = false;
            seenZero = false;
            seenUnconditionalNonZero = false;
            furthestBranchTarget = -1;
            sawConstant = null;
            try {
                super.visitCode(obj);
                if (!seenZero || seenUnconditionalNonZero || (obj.getCode().length > 2)) {
                    boolean seenAll = seenNegative & seenPositive & seenZero;
                    if (!seenAll || seenUnconditionalNonZero) {
                        bugReporter.reportBug(
                                new BugInstance(this, BugType.SCRV_SUSPICIOUS_COMPARATOR_RETURN_VALUES.name(), seenAll ? LOW_PRIORITY : NORMAL_PRIORITY)
                                        .addClass(this).addMethod(this).addSourceLine(this, 0));
                    }
                }
            } catch (StopOpcodeParsingException e) {
                // indeterminate
            }
        }
    }

    /**
     * implements the visitor to look for returns of constant values, and records them for being negative, zero or positive. It also records unconditional
     * returns of non zero values
     *
     * @param seen
     *            the currently parsed opcode
     */
    @Override
    public void sawOpcode(int seen) {
        try {
            stack.precomputation(this);

            switch (seen) {
                case Const.IRETURN: {
                    processIntegerReturn();
                }
                break;

                case Const.GOTO:
                case Const.GOTO_W: {
                    if (stack.getStackDepth() > 0) {
                        throw new StopOpcodeParsingException();
                    }
                    if (furthestBranchTarget < getBranchTarget()) {
                        furthestBranchTarget = getBranchTarget();
                    }
                }
                break;

                case Const.IFEQ:
                case Const.IFNE:
                case Const.IFLT:
                case Const.IFGE:
                case Const.IFGT:
                case Const.IFLE:
                case Const.IF_ICMPEQ:
                case Const.IF_ICMPNE:
                case Const.IF_ICMPLT:
                case Const.IF_ICMPGE:
                case Const.IF_ICMPGT:
                case Const.IF_ICMPLE:
                case Const.IF_ACMPEQ:
                case Const.IF_ACMPNE:
                case Const.IFNULL:
                case Const.IFNONNULL: {
                    if (furthestBranchTarget < getBranchTarget()) {
                        furthestBranchTarget = getBranchTarget();
                    }
                }
                break;

                case Const.LOOKUPSWITCH:
                case Const.TABLESWITCH: {
                    int defTarget = getDefaultSwitchOffset() + getPC();
                    if (furthestBranchTarget > defTarget) {
                        furthestBranchTarget = defTarget;
                    }
                }
                break;

                case Const.ATHROW: {
                    if (stack.getStackDepth() > 0) {
                        OpcodeStack.Item item = stack.getStackItem(0);
                        String exSig = item.getSignature();
                        if ("Ljava/lang/UnsupportedOperationException;".equals(exSig)) {
                            throw new StopOpcodeParsingException();
                        }
                    }
                }
                break;

                /*
                 * these three opcodes are here because findbugs proper is broken, it sometimes
                 * doesn't push this constant on the stack, because of bad branch handling
                 */
                case Const.ICONST_0:
                    if (getNextOpcode() == Const.IRETURN) {
                        sawConstant = Integer.valueOf(0);
                    }
                break;

                case Const.ICONST_M1:
                    if (getNextOpcode() == Const.IRETURN) {
                        sawConstant = Integer.valueOf(-1);
                    }
                break;

                case Const.ICONST_1:
                    if (getNextOpcode() == Const.IRETURN) {
                        sawConstant = Integer.valueOf(1);
                    }
                break;

                default:
                break;

            }
        } finally {
            stack.sawOpcode(this, seen);
        }
    }

    /**
     * processes an IRETURN looking for Const and categorizes them as negative, zero or positive. it also records a unconditional return of a non zero value
     * Throws StopOpcodeParsingException if a return value (constant) is indeterminate
     *
     */
    private void processIntegerReturn() {
        if ((sawConstant == null) && (stack.getStackDepth() == 0)) {
            throw new StopOpcodeParsingException();
        }

        Integer returnValue = null;
        if (sawConstant == null) {
            OpcodeStack.Item item = stack.getStackItem(0);
            returnValue = (Integer) item.getConstant();
        } else {
            returnValue = sawConstant;
        }

        if (returnValue == null) {
            throw new StopOpcodeParsingException();
        }
        int v = returnValue.intValue();
        if (v < 0) {
            seenNegative = true;
            if (getPC() > furthestBranchTarget) {
                seenUnconditionalNonZero = true;
            }
        } else if (v > 0) {
            seenPositive = true;
            if (getPC() > furthestBranchTarget) {
                seenUnconditionalNonZero = true;
            }
        } else {
            seenZero = true;
        }

        sawConstant = null;
    }
}

/**
 * represents patterns of methods to look for to find suspicious compares
 */
class CompareSpec {

    private final JavaClass compareClass;
    private final MethodInfo methodInfo;

    public CompareSpec(@SlashedClassName String compareClassName, MethodInfo mInfo) throws ClassNotFoundException {
        compareClass = Repository.lookupClass(compareClassName);
        methodInfo = mInfo;
    }

    public JavaClass getCompareClass() {
        return compareClass;
    }

    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    @Override
    public String toString() {
        return ToString.build(this);
    }
}

/**
 * a simple data class that holds information about a method call
 */
class MethodInfo {
    final String methodName;
    final int argumentCount;
    final String signatureEnding;

    /**
     * simple constructor for initializing the data
     *
     * @param methodName
     *            the name of the method
     * @param argumentCount
     *            the number of parameters
     * @param signatureEnding
     *            the return value signature type
     */
    MethodInfo(String methodName, int argumentCount, String signatureEnding) {
        this.methodName = methodName;
        this.argumentCount = argumentCount;
        this.signatureEnding = signatureEnding;
    }

    @Override
    public int hashCode() {
        return methodName.hashCode() ^ argumentCount ^ signatureEnding.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MethodInfo)) {
            return false;
        }

        MethodInfo that = (MethodInfo) o;
        return (argumentCount == that.argumentCount) && methodName.equals(that.methodName) && signatureEnding.equals(that.signatureEnding);
    }

    @Override
    public String toString() {
        return ToString.build(this);
    }
}
