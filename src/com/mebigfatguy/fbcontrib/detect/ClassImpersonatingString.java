/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2014 Dave Brosius
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

import org.apache.bcel.classfile.Code;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * looks for string fields that appear to be built with parsing or calling
 * toString() on another object, or from objects that are fields.
 */
@CustomUserValue
public class ClassImpersonatingString extends BytecodeScanningDetector {

	private static final String TO_STRING = "toString";
	private BugReporter bugReporter;
	private OpcodeStack stack;
	
	public ClassImpersonatingString(BugReporter reporter) {
		bugReporter = reporter;
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
		stack.resetForMethodEntry(this);
		super.visitCode(obj);
	}
	
	@Override
	public void sawOpcode(int seen) {
		String userValue = null;
		try {
			stack.precomputation(this);
			switch (seen) {
				case INVOKEVIRTUAL: {
					String clsName = getClassConstantOperand();
					String methodName = getNameConstantOperand();
					String sig = getSigConstantOperand();
					boolean isStringBuilder = "java/lang/StringBuilder".equals(clsName) || "java/lang/StringBuffer".equals(clsName);
					
					if (TO_STRING.equals(methodName) && "()Ljava/lang/String;".equals(sig)) {
						if (isStringBuilder) {
							if (stack.getStackDepth() > 0) {
								OpcodeStack.Item item = stack.getStackItem(0);
								userValue = (String) item.getUserValue();
							}
						} else {
							userValue = TO_STRING;
						}
					} else if (isStringBuilder && "append".equals(methodName)) {
						if (stack.getStackDepth() > 0) {
							OpcodeStack.Item item = stack.getStackItem(0);
							userValue = (String) item.getUserValue();
							if (userValue == null) {
								if (!"Ljava/lang/String;".equals(item.getSignature())) {
									if (stack.getStackDepth() > 1) {
										item = stack.getStackItem(1);
										item.setUserValue(TO_STRING);
									}
								}
							}
						}
					}
				}
				break;
				
				case PUTFIELD:
					if (stack.getStackDepth() > 0) {
						OpcodeStack.Item item = stack.getStackItem(0);
						if ("toString".equals(item.getUserValue())) {
							bugReporter.reportBug(new BugInstance(this, "CIS_TOSTRING_STORED_IN_FIELD", NORMAL_PRIORITY)
										.addClass(this)
										.addMethod(this)
										.addSourceLine(this));
						}
					}
					
			}
		} finally {
			stack.sawOpcode(this, seen);
			if (userValue != null) {
				if (stack.getStackDepth() > 0) {
					OpcodeStack.Item item = stack.getStackItem(0);
					item.setUserValue(userValue);
				}
			}
		}
	}
}
