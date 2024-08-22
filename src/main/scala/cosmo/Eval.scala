package cosmo

import ir._

final class DefId(val id: Int) extends AnyVal

class Scopes {
  var scopes: List[Map[String, DefId]] = List(Map())

  def push() = {
    scopes = Map() :: scopes
  }

  def pop() = {
    scopes = scopes.tail
  }

  def withScope[T](f: => T): T = {
    push()
    val result = f
    pop()
    result
  }

  def get(name: String): Option[DefId] = {
    scopes.find(_.contains(name)).flatMap(_.get(name))
  }

  def set(name: String, value: DefId) = {
    scopes = scopes.updated(0, scopes.head.updated(name, value))
  }
}

private val nameJoiner = "::";

class DefInfo(
    val name: String,
    val noMangle: Boolean,
    val namespaces: List[String],
    var upperBounds: List[Type],
    var lowerBounds: List[Type],
) {
  def nameStem(disambiguator: Int) =
    if (noMangle) name
    else mangledName(disambiguator)
  def fullName(disambiguator: Int) =
    if (noMangle) name
    else fullMangledName(disambiguator)
  // todo: ${disambiguator}
  def mangledName(disambiguator: Int) = name
  def fullMangledName(disambiguator: Int) =
    (namespaces :+ s"${name}").mkString(nameJoiner)
}

class Eval {
  var defAlloc = 0
  var defs: Map[DefId, DefInfo] = Map()
  var items: Map[DefId, Item] = Map()
  var scopes = new Scopes()
  var errors: List[String] = List()
  var module: ir.Region = Region(List())
  var ns: List[String] = List()

  def eval(ast: syntax.Block): Eval = {
    newBuiltin("println")

    module = block(ast)
    this
  }

  def newDefWithInfo(
      name: String,
      noMangle: Boolean = false,
  ): (DefId, DefInfo) = {
    defAlloc += 1
    val id = new DefId(defAlloc)
    scopes.set(name, id)
    val info = new DefInfo(name, noMangle, ns, List(), List())
    defs += (id -> info)
    (id, info)
  }

  def newDef(name: String, noMangle: Boolean = false): DefId = {
    val (id, info) = newDefWithInfo(name, noMangle)
    id
  }

  def newBuiltin(name: String) = {
    val id = newDef(name, noMangle = true)
    items += (id -> Opaque(s"$name"))
  }

  def withNs[T](ns: String)(f: => T): T = {
    this.ns = ns :: this.ns
    val result = f
    this.ns = this.ns.tail
    result
  }

  def block(ast: syntax.Block) = {
    val stmts = scopes.withScope {
      ast.stmts.map(expr)
    }

    Region(stmts)
  }

  def caseBlock(ast: syntax.CaseBlock) = {
    // , target: ir.Item
    val stmts = scopes.withScope {
      ast.stmts.map { case syntax.Case(cond, body) =>
        val condExpr = expr(cond)
        val bodyExpr = body.map(expr).getOrElse(NoneItem)
        Case(condExpr, bodyExpr)
      }
    }

    Region(stmts)
  }

  def classBlock(ast: syntax.Block, info: DefInfo, id: DefId) = {
    val stmts = scopes.withScope {
      withNs(info.name) {
        ast.stmts.flatMap {
          case syntax.Val(name, ty, init) =>
            Some(varItem(name, ty, init, true))
          case syntax.Var(name, ty, init) =>
            Some(varItem(name, ty, init, false))
          case syntax.Def(name, params, rhs) =>
            Some(defItem(syntax.Def(name, params, rhs)))
          case _ =>
            errors = "Invalid class body" :: errors
            None
        }
      }
    }

    Region(stmts)
  }

  def enumClassBlock(ast: syntax.CaseBlock, info: DefInfo, id: DefId) = {
    val stmts = scopes.withScope {
      withNs(info.name) {
        ast.stmts.map { case syntax.Case(cond, body) =>
          val (subName, params) = cond match {
            case syntax.Ident(name)                       => (name, List())
            case syntax.Apply(syntax.Ident(name), params) => (name, params)
            case _                                        => ("invalid", List())
          }

          val vars = params.zipWithIndex.map {
            case (n: syntax.Ident, index) =>
              val ty = if (n.name == info.name) {
                syntax.Self
              } else {
                n
              }
              syntax.Var(s"_${index}", Some(ty), None)
            case (_, index) => syntax.Var(s"_${index}", None, None)
          }

          val b = (body, vars) match {
            case (_, Nil) => body.getOrElse(syntax.Block(List()))
            case (Some(syntax.Block(bc)), vars) => syntax.Block(vars ++ bc)
            case (Some(n), vars)                => syntax.Block(vars :+ n)
            case _                              => syntax.Block(vars)
          }

          classItem(syntax.Class(subName, b))
        }
      }
    }

    Region(Opaque(s"using self_t = ${info.fullName(id.id)}*") :: stmts)
  }

  def varItem(
      name: String,
      ty: Option[syntax.Node],
      init: Option[syntax.Node],
      isContant: Boolean,
  ): ir.Item = {
    val initExpr = init.map(expr).getOrElse(NoneItem)
    val id = newDef(name)
    items += (id -> initExpr)
    ty.map(ty_) match {
      case Some(initTy) =>
        defs(id).upperBounds = defs(id).upperBounds :+ initTy
      case None =>
    }
    ir.Var(id, initExpr, isContant)
  }

  def defItem(ast: syntax.Def) = {
    val syntax.Def(name, params, rhs) = ast
    val result = scopes.withScope {
      params.foreach { p =>
        val syntax.Param(name, ty, init) = p

        val id = newDef(name)

        ty.map(ty_).map { case initTy =>
          defs(id).upperBounds = defs(id).upperBounds :+ initTy
        }
        // todo: infer type from initExpr and body
        val initExpr = init.map(expr).getOrElse(Variable(id))

        items += (id -> initExpr)
      }

      val value = expr(rhs)

      Fn(
        params.map(p =>
          val id = scopes.get(p.name).get
          // todo: compute canonical type
          Param(p.name, id, defs(id).upperBounds.head),
        ),
        Some(value),
      )
    }
    var id = newDef(name)
    items += (id -> result)
    ir.Def(id)
  }

  def classItem(ast: syntax.Class): ir.Def = {
    val syntax.Class(name, body) = ast
    val (id, defInfo) = newDefWithInfo(name)
    val result = scopes.withScope {
      body match
        case body: syntax.Block =>
          classBlock(body, defInfo, id)
        case caseBlock: syntax.CaseBlock =>
          enumClassBlock(caseBlock, defInfo, id)
        case _ =>
          errors = "Invalid class body" :: errors
          Region(List())
    }
    var cls = ir.Class(id, Some(result))
    items += (id -> cls)
    ir.Def(id)
  }

  def ty_(ast: syntax.Node): Type = {
    ast match {
      case syntax.Ident(name) =>
        name match
          case "i8" | "i16" | "i32" | "i64" | "u8" | "u16" | "u32" | "u64" =>
            IntegerTy.parse(name).get
          case name =>
            scopes.get(name) match {
              case Some(id) =>
                TypeVariable(name, id)
              case None =>
                TopTy
            }
      case syntax.Self => SelfTy
      case _           => TopTy
    }
  }

  def expr(ast: syntax.Node): ir.Item = {
    ast match {
      case b: syntax.Block => block(b)
      case b: syntax.CaseBlock =>
        errors = s"case block without match" :: errors
        Opaque(s"0/* error: case block without match */")
      case b: syntax.Match =>
        val lhs = expr(b.lhs)
        val rhs = b.rhs match {
          case b: syntax.CaseBlock => caseBlock(b)
          case b: syntax.Block =>
            if (b.stmts.isEmpty) Region(List())
            else {
              errors = s"match body contains non-case items" :: errors;
              Region(List())
            }
          case _ => {
            errors = s"Invalid match body" :: errors;
            Region(List())
          }
        }
        Match(lhs, rhs)
      case syntax.Val(name, ty, init) =>
        varItem(name, ty, init, true)
      case syntax.Var(name, ty, init) =>
        varItem(name, ty, init, false)
      case d: syntax.Def =>
        defItem(d)
      case c: syntax.Class =>
        classItem(c)
      case syntax.Literal(value) => Opaque(value.toString)
      case syntax.Self           => SelfItem
      case syntax.Ident(name) =>
        scopes.get(name) match {
          case Some(id) => Variable(id)
          case None => errors = s"Undefined variable $name" :: errors; Lit(0)
        }
      case syntax.Param(name, ty, init) =>
        val initExp = init.map(init => s" = ${opa(init)}").getOrElse("")
        Opaque(s"int $name${initExp};")
      case syntax.BinOp(op, lhs, rhs) =>
        BinOp(op, expr(lhs), expr(rhs))
      case syntax.Apply(lhs, rhs) =>
        Apply(expr(lhs), rhs.map(expr))
      case syntax.Return(value) =>
        Return(expr(value))
      case syntax.Case(cond, body) =>
        errors = s"case clause without match" :: errors
        Opaque(s"/* error: case clause without match */")
    }
  }

  def opa(ast: syntax.Node): String = {
    expr(ast) match {
      case Opaque(value) => value
      case _             => ""
    }
  }
}
