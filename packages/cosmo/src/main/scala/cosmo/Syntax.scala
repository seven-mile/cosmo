package cosmo.syntax

import cosmo.DefId

sealed abstract class Node
object Self extends Node
final case class Ident(name: String) extends Node
final case class Literal(value: Int) extends Node
final case class Block(stmts: List[Node]) extends Node
final case class Val(name: String, ty: Option[Node], init: Option[Node])
    extends Node
final case class Var(name: String, ty: Option[Node], init: Option[Node])
    extends Node
final case class Class(name: String, body: Node) extends Node
final case class Param(name: String, ty: Option[Node], init: Option[Node])
    extends Node
final case class Def(name: String, params: Option[List[Param]], rhs: Node)
    extends Node
final case class Import(path: String) extends Node
final case class Loop(body: Node) extends Node
final case class For(name: String, iter: Node, body: Node) extends Node
final case class Break() extends Node
final case class Continue() extends Node
final case class If(cond: Node, cont_bb: Node, else_bb: Option[Node])
    extends Node
final case class BinOp(op: String, lhs: Node, rhs: Node) extends Node
final case class Match(lhs: Node, rhs: Node) extends Node
final case class Select(lhs: Node, rhs: Ident) extends Node
final case class Apply(lhs: Node, rhs: List[Node]) extends Node
final case class Return(value: Node) extends Node
final case class CaseBlock(stmts: List[Case]) extends Node
final case class Case(cond: Node, body: Option[Node]) extends Node
