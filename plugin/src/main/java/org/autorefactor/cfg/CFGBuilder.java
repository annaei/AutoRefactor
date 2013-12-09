/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2013 Jean-Noël Rouvignac - initial API and implementation
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.cfg;

import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.autorefactor.refactoring.ASTHelper;
import org.autorefactor.util.NotImplementedException;
import org.autorefactor.util.Pair;
import org.autorefactor.util.UnhandledException;
import org.eclipse.jdt.core.dom.*;

import static org.autorefactor.cfg.ASTPrintHelper.*;
import static org.autorefactor.cfg.CFGEdgeBuilder.*;
import static org.autorefactor.cfg.VariableAccess.*;

/**
 * Builds a CFG.
 * <p>
 * Look at {@link #buildCFG(IfStatement, List, CFGBasicBlock)} for a javadoc for
 * all the buildCFG(*Statement, List<CFGEdgeBuilder>, CFGBasicBlock) methods.
 * <p>
 * TODO JNR detect dead code by looking for empty live blocks list when visiting a node
 * + looking at if / while / etc. conditions and see if they resolve to a constant
 */
public class CFGBuilder {

	private String source;
	private int tabSize;
	/**
	 * Edges to be built after visiting the statement used as the key.
	 * <p>
	 * After a statement is visited, code checks whether there are edges to
	 * build and creates them.
	 * </p>
	 * <p>
	 * This is only useful when labels are used with break or continue
	 * statements which can send control flow back to any parent statement.
	 * </p>
	 */
	private final Map<Statement, Map<CFGEdgeBuilder, Boolean>> edgesToBuild =
			new HashMap<Statement, Map<CFGEdgeBuilder, Boolean>>();
	/** The exit block for the CFG being built */
	private CFGBasicBlock exitBlock;

	public CFGBuilder(String source, int tabSize) {
		this.source = source;
		this.tabSize = tabSize;
	}

	@SuppressWarnings("unchecked")
	private void addVariableAccess(CFGBasicBlock basicBlock, Expression node,
			int flags) {
		if (node == null) {
			return;
		} else if (node instanceof ArrayAccess) {
			ArrayAccess aa = (ArrayAccess) node;
			addVariableAccess(basicBlock, aa.getArray(), flags);
			addVariableAccess(basicBlock, aa.getIndex(), flags);
		} else if (node instanceof ArrayCreation) {
			ArrayCreation ac = (ArrayCreation) node;
			addVariableAccess(basicBlock, ac.getInitializer(), flags);
			addVariableAccesses(basicBlock, ac.dimensions(), flags);
		} else if (node instanceof ArrayInitializer) {
			ArrayInitializer ai = (ArrayInitializer) node;
			addVariableAccesses(basicBlock, ai.expressions(), flags);
		} else if (node instanceof Assignment) {
			Assignment a = (Assignment) node;
			addVariableAccess(basicBlock, a.getLeftHandSide(), WRITE);
			addVariableAccess(basicBlock, a.getRightHandSide(), READ);
		} else if (node instanceof BooleanLiteral
				|| node instanceof CharacterLiteral
				|| node instanceof NullLiteral || node instanceof NumberLiteral
				|| node instanceof StringLiteral || node instanceof TypeLiteral) {
			// nothing to do
		} else if (node instanceof CastExpression) {
			CastExpression ce = (CastExpression) node;
			addVariableAccess(basicBlock, ce.getExpression(), flags);
		} else if (node instanceof ClassInstanceCreation) {
			ClassInstanceCreation cic = (ClassInstanceCreation) node;
			addVariableAccess(basicBlock, cic.getExpression(), flags);
			addVariableAccesses(basicBlock, cic.arguments(), flags);
		} else if (node instanceof ConditionalExpression) {
			ConditionalExpression ce = (ConditionalExpression) node;
			addVariableAccess(basicBlock, ce.getExpression(), flags);
			addVariableAccess(basicBlock, ce.getThenExpression(), flags);
			addVariableAccess(basicBlock, ce.getElseExpression(), flags);
		} else if (node instanceof FieldAccess) {
			FieldAccess fa = (FieldAccess) node;
			basicBlock.addVariableAccess(new VariableAccess(fa, flags));
		} else if (node instanceof InfixExpression) {
			InfixExpression ie = (InfixExpression) node;
			addVariableAccess(basicBlock, ie.getLeftOperand(), flags);
			addVariableAccess(basicBlock, ie.getRightOperand(), flags);
		} else if (node instanceof InstanceofExpression) {
			InstanceofExpression ie = (InstanceofExpression) node;
			addVariableAccess(basicBlock, ie.getLeftOperand(), flags);
		} else if (node instanceof MethodInvocation) {
			MethodInvocation mi = (MethodInvocation) node;
			addVariableAccess(basicBlock, mi.getExpression(), flags);
			addVariableAccesses(basicBlock, mi.arguments(), flags);
		} else if (node instanceof SimpleName) {
			SimpleName sn = (SimpleName) node;
			basicBlock.addVariableAccess(new VariableAccess(sn, flags));
		} else if (node instanceof QualifiedName) {
			QualifiedName qn = (QualifiedName) node;
			basicBlock.addVariableAccess(new VariableAccess(qn, flags));
		} else if (node instanceof ParenthesizedExpression) {
			ParenthesizedExpression pe = (ParenthesizedExpression) node;
			addVariableAccess(basicBlock, pe.getExpression(), flags);
		} else if (node instanceof PostfixExpression) {
			PostfixExpression pe = (PostfixExpression) node;
			addVariableAccess(basicBlock, pe.getOperand(), flags);
		} else if (node instanceof PrefixExpression) {
			PrefixExpression pe = (PrefixExpression) node;
			addVariableAccess(basicBlock, pe.getOperand(), flags);
		} else if (node instanceof SuperFieldAccess) {
			SuperFieldAccess sfa = (SuperFieldAccess) node;
			addVariableAccess(basicBlock, sfa.getQualifier(), flags);
			addVariableAccess(basicBlock, sfa.getName(), flags);
		} else if (node instanceof SuperMethodInvocation) {
			SuperMethodInvocation smi = (SuperMethodInvocation) node;
			addVariableAccess(basicBlock, smi.getQualifier(), flags);
			addVariableAccess(basicBlock, smi.getName(), flags);
		} else if (node instanceof ThisExpression) {
			ThisExpression te = (ThisExpression) node;
			// TODO JNR remember use of "this" here
			addVariableAccess(basicBlock, te.getQualifier(), flags);
		} else if (node instanceof VariableDeclarationExpression) {
			addDeclarations(basicBlock, (VariableDeclarationExpression) node);
		} else {
			throw new NotImplementedException(node);
		}
	}

	private void addVariableAccesses(CFGBasicBlock basicBlock,
			List<Expression> expressions, int flags) {
		for (Expression exor : expressions) {
			addVariableAccess(basicBlock, exor, flags);
		}
	}

	private void addDeclarations(CFGBasicBlock basicBlock,
			List<VariableDeclarationFragment> fragments, Type type) {
		for (VariableDeclarationFragment vdf : fragments) {
			addDeclaration(basicBlock, vdf, type);
		}
	}

	private void addDeclaration(final CFGBasicBlock basicBlock,
			VariableDeclarationFragment vdf, Type type) {
		final int accessType = vdf.getInitializer() == null ? DECL_UNINIT
				: DECL_INIT | WRITE;
		basicBlock.addVariableAccess(new VariableAccess(vdf, vdf.getName(),
				type, accessType));
	}

	private void addDeclarations(CFGBasicBlock basicBlock,
			final VariableDeclarationExpression vde) {
		addDeclarations(basicBlock, vde.fragments(), vde.getType());

	}

	private void addDeclarations(CFGBasicBlock basicBlock,
			List<SingleVariableDeclaration> varDecls) {
		for (SingleVariableDeclaration varDecl : varDecls) {
			addDeclaration(basicBlock, varDecl);
		}
	}

	private void addDeclaration(final CFGBasicBlock basicBlock,
			final SingleVariableDeclaration varDecl) {
		addDeclaration(basicBlock, varDecl, DECL_INIT);
	}

	private void addDeclaration(CFGBasicBlock basicBlock,
			SingleVariableDeclaration varDecl, int flags) {
		basicBlock.addVariableAccess(new VariableAccess(varDecl, varDecl
				.getName(), varDecl.getType(), flags));
	}

	@SuppressWarnings("unchecked")
	private List<CFGEdgeBuilder> buildCFG(Statement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlock) {
		if (node == null) {
			return Collections.emptyList();
		}
		try {
			final Method m = getClass().getMethod("buildCFG", node.getClass(), List.class, CFGBasicBlock.class);
			return (List<CFGEdgeBuilder>) m.invoke(this, node, liveBlocks, currentBasicBlock);
		} catch (Exception e) {
			throw new UnhandledException(e);
		}
	}

	public void buildCFG(QualifiedName node) {
		throw new NotImplementedException();
	}

	public void buildCFG(PrimitiveType node) {
		throw new NotImplementedException();
	}

	public void buildCFG(QualifiedType node) {
		throw new NotImplementedException();
	}

	public void buildCFG(PrefixExpression node) {
		throw new NotImplementedException();
	}

	public void buildCFG(PostfixExpression node) {
		throw new NotImplementedException();
	}

	public void buildCFG(ParenthesizedExpression node) {
		throw new NotImplementedException();
	}

	public void buildCFG(SingleVariableDeclaration node) {
		throw new NotImplementedException();
	}

	public void buildCFG(SimpleType node) {
		throw new NotImplementedException();
	}

	public void buildCFG(SimpleName node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(ReturnStatement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		if (node.getExpression() != null) {
			addVariableAccess(basicBlock, node.getExpression(), READ);
		}
		buildEdge(basicBlock, this.exitBlock);
		return Collections.emptyList();
	}

	public void buildCFG(Modifier node) {
		throw new NotImplementedException();
	}

	public void buildCFG(MethodInvocation node) {
		// TODO JNR add variable access to "this"
		throw new NotImplementedException();
	}

	public CFGBasicBlock buildCFG(MethodDeclaration node) {
		final CFGBasicBlock entryBlock = newEntryBlock(node);
		this.exitBlock = newExitBlock(node);

		addDeclarations(entryBlock, node.parameters());

		try {
			final List<CFGEdgeBuilder> liveBlocks = newList(new CFGEdgeBuilder(entryBlock));
			final List<CFGEdgeBuilder> liveAfterBody = buildCFG(node.getBody(), liveBlocks, null);
			if (!liveAfterBody.isEmpty()) {
				if (node.getReturnType2() == null
						|| "void".equals(node.getReturnType2().resolveBinding().getName())) {
					for (CFGEdgeBuilder builder : liveAfterBody) {
						builder.withTarget(exitBlock).build();
					}
				} else {
					throw new IllegalStateException("Did not expect to find any edges to build for a constructor or a non void method return type.");
				}
			}
			if (!this.edgesToBuild.isEmpty()) {
				throw new IllegalStateException(
						"At this point, there should not be any edges left to build. Left edges: " + this.edgesToBuild);
			}
			// new CFGDotPrinter().toDot(entryBlock);
			// new CodePathCollector().getPaths(entryBlock);
			return entryBlock;
		} finally {
			this.exitBlock = null;
		}
	}

	public void buildCFG(MethodRefParameter node) {
		throw new NotImplementedException();
	}

	public void buildCFG(MethodRef node) {
		throw new NotImplementedException();
	}

	public void buildCFG(MemberValuePair node) {
		throw new NotImplementedException();
	}

	public void buildCFG(ParameterizedType node) {
		throw new NotImplementedException();
	}

	public void buildCFG(NumberLiteral node) {
		throw new NotImplementedException();
	}

	public void buildCFG(NullLiteral node) {
		throw new NotImplementedException();
	}

	public void buildCFG(UnionType node) {
		throw new NotImplementedException();
	}

	public void buildCFG(TypeParameter node) {
		throw new NotImplementedException();
	}

	public void buildCFG(TypeLiteral node) {
		throw new NotImplementedException();
	}

	public void buildCFG(TypeDeclarationStatement node) {
		throw new NotImplementedException();
	}

	public CFGBasicBlock buildCFG(TypeDeclaration node) {
		if (!node.isInterface()) {
			for (FieldDeclaration fieldDecl : node.getFields()) {
				buildCFG(fieldDecl);
			}
			for (MethodDeclaration methodDecl : node.getMethods()) {
				buildCFG(methodDecl);
			}
			for (TypeDeclaration typeDeclaration : node.getTypes()) {
				buildCFG(typeDeclaration);
			}
			// for (BodyDeclaration bodyDeclaration : (List<BodyDeclaration>)
			// node.bodyDeclarations()) {
			// buildCFG(bodyDeclaration);
			// }
		}
		return null;
	}

	public void buildCFG(TryStatement node) {
		throw new NotImplementedException();
	}

	public void buildCFG(WildcardType node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(WhileStatement node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock conditionBlock = getCFGBasicBlock(node.getExpression(), null, liveBlocks);
		addVariableAccess(conditionBlock, node.getExpression(), READ);

		final List<CFGEdgeBuilder> liveBlock = newList(new CFGEdgeBuilder(node.getExpression(), true, conditionBlock));
		final List<CFGEdgeBuilder> liveAfterStmt = buildCFG(node.getBody(), liveBlock, null);
		liveAfterStmt.add(new CFGEdgeBuilder(node.getExpression(), false, conditionBlock));
		buildEdgesAfterBranchableStmt(node, liveAfterStmt, conditionBlock);
		return liveAfterStmt;
	}

	public void buildCFG(VariableDeclarationFragment node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(VariableDeclarationStatement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		addDeclarations(basicBlock, node.fragments(), node.getType());
		return getInBlockStmtResult(liveBlocks, currentBasicBlock, basicBlock);
	}

	public void buildCFG(VariableDeclarationExpression node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(SwitchStatement node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		final List<CFGEdgeBuilder> liveBeforeBody = newList(new CFGEdgeBuilder(basicBlock));
		final List<CFGEdgeBuilder> liveAfterBody = buildCFG(node.statements(), basicBlock, liveBeforeBody);
		liveAfterBody.add(new CFGEdgeBuilder(basicBlock));

		buildEdgesAfterBranchableStmt(node, liveAfterBody, basicBlock);
		return liveAfterBody;
	}

	public List<CFGEdgeBuilder> buildCFG(SwitchCase node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		// the current live blocks will be empty if there was a break,
		// or populated in case of fall-through.
		// copy the list to avoid adding to unmodifiable lists
		liveBlocks = new ArrayList<CFGEdgeBuilder>(liveBlocks);
		// add an edge going from the condition of the switch
		// (startBlock is the condition of the switch)
		liveBlocks.add(new CFGEdgeBuilder(node.getExpression(), true, currentBasicBlock));
		return liveBlocks;
	}

	public void buildCFG(SuperMethodInvocation node) {
		throw new NotImplementedException();
	}

	public void buildCFG(SuperFieldAccess node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(SuperConstructorInvocation node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		addVariableAccesses(basicBlock, node.arguments(), READ);
		return getInBlockStmtResult(liveBlocks, currentBasicBlock, basicBlock);
	}

	public void buildCFG(StringLiteral node) {
		throw new NotImplementedException();
	}

	public void buildCFG(ThrowStatement node) {
		throw new NotImplementedException();
	}

	public void buildCFG(ThisExpression node) {
		throw new NotImplementedException();
	}

	public void buildCFG(TextElement node) {
		throw new NotImplementedException();
	}

	public void buildCFG(TagElement node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(SynchronizedStatement node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		CFGBasicBlock basicBlock = getCFGBasicBlock(node, null, liveBlocks);
		addVariableAccess(basicBlock, node.getExpression(), READ);
		List<CFGEdgeBuilder> liveBlock = newList(new CFGEdgeBuilder(basicBlock));
		return buildCFG(node.getBody(), liveBlock, null);
	}

	public void buildCFG(CatchClause node) {
		throw new NotImplementedException();
	}

	public void buildCFG(CastExpression node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(BreakStatement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		final Statement targetStmt;
		if (node.getLabel() != null) {
			targetStmt = findLabeledParentStmt(node);
		} else {
			targetStmt = findBreakableParentStmt(node);
		}
		addEdgeToBuild(targetStmt, new CFGEdgeBuilder(basicBlock), true);
		return Collections.emptyList();
	}

	private Statement findLabeledParentStmt(ASTNode node) {
		ASTNode n = node;
		while (n != null && !(n instanceof LabeledStatement)) {
			n = n.getParent();
		}
		if (n != null) {
			return ((LabeledStatement) n).getBody();
		}
		return null;
	}

	private Statement findBreakableParentStmt(ASTNode node) {
		ASTNode n = node;
		while (n != null && !ASTHelper.isBreakable(n)) {
			n = n.getParent();
		}
		if (n != null) {
			return (Statement) n;
		}
		return null;
	}

	public void buildCFG(BooleanLiteral node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(ConstructorInvocation node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		addVariableAccesses(basicBlock, node.arguments(), READ);
		return getInBlockStmtResult(liveBlocks, currentBasicBlock, basicBlock);
	}

	public void buildCFG(ConditionalExpression node) {
		throw new NotImplementedException();
	}

	public List<CFGBasicBlock> buildCFG(CompilationUnit node) {
		List<CFGBasicBlock> results = new LinkedList<CFGBasicBlock>();
		for (AbstractTypeDeclaration decl : (List<AbstractTypeDeclaration>) node.types()) {
			if (decl instanceof TypeDeclaration) {
				results.add(buildCFG((TypeDeclaration) decl));
			} else {
				throw new NotImplementedException(node);
			}
		}
		return results;
	}

	public void buildCFG(ClassInstanceCreation node) {
		throw new NotImplementedException();
	}

	public void buildCFG(CharacterLiteral node) {
		throw new NotImplementedException();
	}

	public void buildCFG(ArrayCreation node) {
		throw new NotImplementedException();
	}

	public void buildCFG(ArrayAccess node) {
		throw new NotImplementedException();
	}

	public void buildCFG(AnonymousClassDeclaration node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(Block node, List<CFGEdgeBuilder> previousLiveBlocks,
			CFGBasicBlock currentBasicBlock) {
		List<CFGEdgeBuilder> liveBlocks = previousLiveBlocks;
		try {
			liveBlocks = buildCFG(node.statements(), currentBasicBlock, liveBlocks);
		} finally {
			moveAllEdgesToBuild(node, liveBlocks);
		}
		return liveBlocks;
	}

	private List<CFGEdgeBuilder> buildCFG(List<Statement> stmts,
			CFGBasicBlock startBlock, List<CFGEdgeBuilder> liveBlocks) {
		CFGBasicBlock basicBlock = startBlock;
		for (Statement stmt : stmts) {
			CFGBasicBlock nextStmtBasicBlock = basicBlock;
			if (stmt instanceof AssertStatement) {
				liveBlocks = buildCFG((AssertStatement) stmt, liveBlocks, basicBlock);
			// } else if (stmt instanceof Block) {
			// buildCFG((Block) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof BreakStatement) {
				liveBlocks = buildCFG((BreakStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof ConstructorInvocation) {
				liveBlocks = buildCFG(stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof ContinueStatement) {
				liveBlocks = buildCFG((ContinueStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof DoStatement) {
				liveBlocks = buildCFG((DoStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof EmptyStatement) {
				liveBlocks = buildCFG((EmptyStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof EnhancedForStatement) {
				liveBlocks = buildCFG((EnhancedForStatement) stmt, liveBlocks, basicBlock);
				nextStmtBasicBlock = null;
			} else if (stmt instanceof ExpressionStatement) {
				liveBlocks = buildCFG((ExpressionStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof ForStatement) {
				liveBlocks = buildCFG((ForStatement) stmt, liveBlocks, basicBlock);
				nextStmtBasicBlock = null;
			} else if (stmt instanceof IfStatement) {
				liveBlocks = buildCFG((IfStatement) stmt, liveBlocks, basicBlock);
				nextStmtBasicBlock = null;
			} else if (stmt instanceof LabeledStatement) {
				liveBlocks = buildCFG((LabeledStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof ReturnStatement) {
				liveBlocks = buildCFG((ReturnStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof SuperConstructorInvocation) {
				liveBlocks = buildCFG(stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof SwitchCase) {
				liveBlocks = buildCFG((SwitchCase) stmt, liveBlocks, startBlock);
				// next statement will always create a new basicBlock
				nextStmtBasicBlock = null;
			} else if (stmt instanceof SwitchStatement) {
				liveBlocks = buildCFG((SwitchStatement) stmt, liveBlocks, basicBlock);
				nextStmtBasicBlock = null;
			} else if (stmt instanceof SynchronizedStatement) {
				liveBlocks = buildCFG((SynchronizedStatement) stmt, liveBlocks, basicBlock);
				nextStmtBasicBlock = null;
				// } else if (stmt instanceof ThrowStatement) {
				// buildCFG((ThrowStatement) stmt, liveBlocks, basicBlock);
				// } else if (stmt instanceof TryStatement) {
				// buildCFG((TryStatement) stmt, liveBlocks, basicBlock);
				// } else if (stmt instanceof TypeDeclarationStatement) {
				// buildCFG((TypeDeclarationStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof VariableDeclarationStatement) {
				liveBlocks = buildCFG((VariableDeclarationStatement) stmt, liveBlocks, basicBlock);
			} else if (stmt instanceof WhileStatement) {
				liveBlocks = buildCFG((WhileStatement) stmt, liveBlocks, basicBlock);
			} else {
				throw new NotImplementedException(stmt);
			}
			basicBlock = nextStmtBasicBlock;
		}
		return liveBlocks;
	}

	public void buildCFG(Assignment node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(AssertStatement node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		addVariableAccess(basicBlock, node.getExpression(), READ);
		addVariableAccess(basicBlock, node.getMessage(), READ);
		return getInBlockStmtResult(liveBlocks, currentBasicBlock, basicBlock);
	}

	public void buildCFG(ArrayType node) {
		throw new NotImplementedException();
	}

	public void buildCFG(ArrayInitializer node) {
		throw new NotImplementedException();
	}

	public void buildCFG(Initializer node) {
		throw new NotImplementedException();
	}

	public void buildCFG(InstanceofExpression node) {
		throw new NotImplementedException();
	}

	public void buildCFG(InfixExpression node) {
		throw new NotImplementedException();
	}

	/**
	 * Builds a CFG for the passed in statement.
	 *
	 * @param node
	 *          the statement for which to build a CFG
	 * @param liveBlocks
	 *          the List of live blocks before the current statement
	 * @param currentBasicBlock
	 *          the current basic block to which the current statement might be
	 *          added. If null, then the a new basic block must be created for the
	 *          current statement
	 * @return the list of live blocks after the current statement
	 */
	public List<CFGEdgeBuilder> buildCFG(IfStatement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlockIgnored) {
		final CFGBasicBlock exprBlock = getCFGBasicBlock(node, null, liveBlocks, true);
		try {
			addVariableAccess(exprBlock, node.getExpression(), READ);

			final List<CFGEdgeBuilder> results = new ArrayList<CFGEdgeBuilder>();
			CFGEdgeBuilder thenEdge = new CFGEdgeBuilder(node.getExpression(), true, exprBlock);
			results.addAll(buildCFG(node.getThenStatement(), newList(thenEdge), null));

			final Statement elseStmt = node.getElseStatement();
			CFGEdgeBuilder elseEdge = new CFGEdgeBuilder(node.getExpression(), false, exprBlock);
			if (elseStmt != null) {
				results.addAll(buildCFG(elseStmt, newList(elseEdge), null));
			} else {
				results.add(elseEdge);
			}
			return results;
		} finally {
			moveAllEdgesToBuild(node, liveBlocks);
		}
	}

	private List<CFGEdgeBuilder> newList(CFGEdgeBuilder... builders) {
		List<CFGEdgeBuilder> result = new ArrayList<CFGEdgeBuilder>();
		for (CFGEdgeBuilder builder : builders) {
			result.add(builder);
		}
		return result;
	}

	private void addEdgeToBuild(final Statement node, CFGEdgeBuilder builder, boolean isBreakStmt) {
		if (builder != null) {
			Map<CFGEdgeBuilder, Boolean> builders = this.edgesToBuild.get(node);
			if (builders == null) {
				builders = new HashMap<CFGEdgeBuilder, Boolean>();
				this.edgesToBuild.put(node, builders);
			}
			builders.put(builder, isBreakStmt);
		}
	}

	private void moveAllEdgesToBuild(Statement node, Collection<CFGEdgeBuilder> liveBlocks) {
		final Map<CFGEdgeBuilder, Boolean> toBuild = this.edgesToBuild.remove(node);
		if (toBuild != null) {
			liveBlocks.addAll(toBuild.keySet());
		}
	}

	private void buildEdges(final Collection<CFGEdgeBuilder> toBuild,
			final CFGBasicBlock targetBlock) {
		if (isNotEmpty(toBuild)) {
			for (CFGEdgeBuilder builder : toBuild) {
				builder.withTarget(targetBlock).build();
			}
		}
	}

	private void buildEdgesAfterBranchableStmt(Statement node,
			final Collection<CFGEdgeBuilder> liveAfterBranchableStmt, final CFGBasicBlock whereToBranchBlock) {
		final Map<CFGEdgeBuilder, Boolean> toBuild = this.edgesToBuild.remove(node);
		if (isNotEmpty(toBuild)) {
			for (Entry<CFGEdgeBuilder, Boolean> entry : toBuild.entrySet()) {
				final CFGEdgeBuilder builder = entry.getKey();
				final boolean isBreakStmt = entry.getValue();
				if (isBreakStmt) {
					liveAfterBranchableStmt.add(builder);
				} else { // this is a continue statement
					builder.withTarget(whereToBranchBlock).build();
				}
			}
		}
	}

	public void buildCFG(MemberRef node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(LabeledStatement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlock) {
		// does not count as an executable node, so do not get a basic block for it
		return buildCFG(node.getBody(), liveBlocks, currentBasicBlock);
	}

	public List<CFGEdgeBuilder> buildCFG(EnhancedForStatement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, null, liveBlocks);

		addDeclaration(basicBlock, node.getParameter(), DECL_INIT | WRITE);

		final List<CFGEdgeBuilder> newLiveBlocks = newList(new CFGEdgeBuilder(basicBlock));
		List<CFGEdgeBuilder> liveAfterBody = buildCFG(node.getBody(), newLiveBlocks, null);
		buildEdges(liveAfterBody, basicBlock);

		final List<CFGEdgeBuilder> liveAfterStmt = newList(new CFGEdgeBuilder(basicBlock));
		buildEdgesAfterBranchableStmt(node, liveAfterStmt, basicBlock);
		return liveAfterStmt;
	}

	public List<CFGEdgeBuilder> buildCFG(EmptyStatement node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		return getInBlockStmtResult(liveBlocks, currentBasicBlock, basicBlock);
	}

	private List<CFGEdgeBuilder> getInBlockStmtResult(List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock, CFGBasicBlock basicBlock) {
		if (currentBasicBlock == null) {
			// new block was created for current node
			return newList(new CFGEdgeBuilder(basicBlock));
		}
		return liveBlocks;
	}

	public List<CFGEdgeBuilder> buildCFG(DoStatement node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, null, liveBlocks);
		List<CFGEdgeBuilder> liveBlock = newList(new CFGEdgeBuilder(basicBlock));
		List<CFGEdgeBuilder> liveAfterLoop = buildCFG(node.getBody(), liveBlock, basicBlock);
		CFGBasicBlock conditionBlock = getCFGBasicBlock(node.getExpression(), null, liveAfterLoop);
		addVariableAccess(conditionBlock, node.getExpression(), READ);

		buildEdge(node.getExpression(), true, conditionBlock, basicBlock);

		List<CFGEdgeBuilder> liveAfterStmt = newList(new CFGEdgeBuilder(node.getExpression(), false, conditionBlock));
		buildEdgesAfterBranchableStmt(node, liveAfterStmt, basicBlock);
		return liveAfterStmt;
	}

	public List<CFGEdgeBuilder> buildCFG(ContinueStatement node, List<CFGEdgeBuilder> liveBlocks,
			CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		final Statement targetStmt;
		if (node.getLabel() != null) {
			targetStmt = findLabeledParentStmt(node);
		} else {
			targetStmt = findContinuableParentStmt(node);
		}
		addEdgeToBuild(targetStmt, new CFGEdgeBuilder(basicBlock), false);
		return Collections.emptyList();
	}

	private Statement findContinuableParentStmt(ASTNode node) {
		ASTNode n = node;
		while (n != null && !ASTHelper.isLoop(n)) {
			n = n.getParent();
		}
		if (n != null) {
			return (Statement) n;
		}
		return null;
	}

	public List<CFGEdgeBuilder> buildCFG(ForStatement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlockIgnored) {
		final CFGBasicBlock initBlock = getCFGBasicBlock(node.initializers(), liveBlocks);
		final List<CFGEdgeBuilder> initLiveBlock = newList(new CFGEdgeBuilder(initBlock));
		final CFGBasicBlock exprBlock = getCFGBasicBlock(node.getExpression(), null, initLiveBlock, true);
		final CFGBasicBlock updatersBlock = getCFGBasicBlock(node.updaters(), Collections.EMPTY_LIST);
		buildEdge(updatersBlock, exprBlock);

		for (Expression expression : (List<Expression>) node.initializers()) {
			if (expression instanceof VariableDeclarationExpression) {
				addDeclarations(initBlock, (VariableDeclarationExpression) expression);
			}
		}
		addVariableAccess(exprBlock, node.getExpression(), READ);
		addVariableAccesses(updatersBlock, node.updaters(), WRITE);

		CFGEdgeBuilder liveBlock = new CFGEdgeBuilder(node.getExpression(), true, exprBlock);

		final List<CFGEdgeBuilder> liveAfterBody = buildCFG(node.getBody(), newList(liveBlock), null);
		buildEdges(liveAfterBody, updatersBlock);

		final List<CFGEdgeBuilder> liveAfterStmt = newList(new CFGEdgeBuilder(
				node.getExpression(), false, exprBlock));
		buildEdgesAfterBranchableStmt(node, liveAfterStmt, updatersBlock);
		return liveAfterStmt;
	}

	public void buildCFG(FieldDeclaration node) {
		throw new NotImplementedException();
	}

	public void buildCFG(FieldAccess node) {
		throw new NotImplementedException();
	}

	public List<CFGEdgeBuilder> buildCFG(ExpressionStatement node,
			List<CFGEdgeBuilder> liveBlocks, CFGBasicBlock currentBasicBlock) {
		final CFGBasicBlock basicBlock = getCFGBasicBlock(node, currentBasicBlock, liveBlocks);
		addVariableAccess(basicBlock, node.getExpression(), READ);
		return getInBlockStmtResult(liveBlocks, currentBasicBlock, basicBlock);
	}

	private CFGBasicBlock getCFGBasicBlock(ASTNode node, CFGBasicBlock currentBasicBlock,
			List<CFGEdgeBuilder> liveBlocks) {
		return getCFGBasicBlock(node, currentBasicBlock, liveBlocks, false);
	}

	/**
	 * Will create and return a new CFGBasicBlock for the passed in node, if the currentBasicBlock is null, otherwise
	 * it will return the currentBasicBlock.
	 *
	 * @param node
	 * @param currentBasicBlock the current basic block the node will be added to. Can be null to force the creation
	 *        of a new CFGBasicBlock.
	 * @param liveBlocks the blocks that are live before getting the CFGBasicBlock
	 * @param isDecision used for building the associated CFGEdge
	 * @return
	 */
	private CFGBasicBlock getCFGBasicBlock(ASTNode node, CFGBasicBlock currentBasicBlock,
			List<CFGEdgeBuilder> liveBlocks, boolean isDecision) {
		final Map<CFGEdgeBuilder, Boolean> toBuild = this.edgesToBuild.remove(node);
		if (isNotEmpty(toBuild)) {
			throw new RuntimeException("No edges to build should exist for node \"" + node
				+ "\" before a CFGBasicBlock is created for it. Found the following edges to build " + toBuild);
		}
		if (currentBasicBlock != null) {
			final CFGBasicBlock basicBlock = currentBasicBlock;
			// TODO JNR add nodes to the basicBlock they belong to
			// and adapt the CFGDotPrinter to display "..." after the first node
			// basicBlock.addNode(node);
			return basicBlock;
		}
		final Pair<Integer, Integer> lineCol = getLineAndColumn(node);
		final CFGBasicBlock basicBlock = new CFGBasicBlock(node,
				getFileName(node), codeExcerpt(node), isDecision,
				lineCol.getFirst(), lineCol.getSecond());
		buildEdges(liveBlocks, basicBlock);
		return basicBlock;
	}

	private CFGBasicBlock getCFGBasicBlock(List<Expression> expressions, List<CFGEdgeBuilder> liveBlocks) {
		if (isNotEmpty(expressions)) {
			final Expression firstExpr = expressions.get(0);
			final Pair<Integer, Integer> lineCol = getLineAndColumn(firstExpr
					.getStartPosition());
			final CFGBasicBlock basicBlock = new CFGBasicBlock(expressions.get(0),
					getFileName(firstExpr), codeExcerpt(expressions), false,
					lineCol.getFirst(), lineCol.getSecond());
			buildEdges(liveBlocks, basicBlock);
			return basicBlock;
		}
		throw new NotImplementedException("for empty expressions list");
	}

	private CFGBasicBlock newEntryBlock(MethodDeclaration node) {
		return CFGBasicBlock.buildEntryBlock(node, getFileName(node),
				codeExcerpt(node));
	}

	private CFGBasicBlock newExitBlock(MethodDeclaration node) {
		final Pair<Integer, Integer> lineCol = getLineAndColumn(node
				.getStartPosition() + node.getLength());
		return CFGBasicBlock.buildExitBlock(node, getFileName(node),
				codeExcerpt(node), lineCol.getFirst(), lineCol.getSecond());
	}

	private Pair<Integer, Integer> getLineAndColumn(ASTNode node) {
		return getLineAndColumn(node.getStartPosition());
	}

	private Pair<Integer, Integer> getLineAndColumn(final int position) {
		// TODO Use CompilationUnit.getLineNumber() and CompilationUnit.getColumnNumber()
		// Return SourceLocation class with also startNodePosition to be used for graph node names
		// line number and column number are then used as comments for the node
		// file starts with line 1
		int lineNo = 1;
		int lastMatchPosition = 0;
		final Matcher matcher = Pattern.compile("\r\n|\r|\n").matcher(source);
		while (matcher.find()) {
			final MatchResult matchResult = matcher.toMatchResult();
			if (matchResult.end() >= position) {
				final String startOfLine = this.source.substring(
						lastMatchPosition, position);
				final int nbChars = countCharacters(startOfLine, tabSize);
				// + 1 because line starts with column 1
				return Pair.of(lineNo, nbChars + 1);
			}
			lastMatchPosition = matchResult.end();
			++lineNo;
		}
		throw new IllegalStateException(
				"A line and column number should have been found");
	}

	private int countCharacters(String s, int tabSize) {
		int result = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == '\t') {
				result += tabSize - (i % tabSize);
			} else {
				result++;
			}
		}
		return result;
	}

	private boolean isNotEmpty(final Collection<?> col) {
		return col != null && !col.isEmpty();
	}

	private boolean isNotEmpty(final Map<?, ?> col) {
		return col != null && !col.isEmpty();
	}

}