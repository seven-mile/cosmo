package cosmo
import scala.scalajs.js

val syntaxOnly = true

class SampleTest extends munit.FunSuite:
  lazy val compiler = {
    var compiler = new Cosmo();
    if (!syntaxOnly) {
      compiler.loadPackage(PackageMetaSource.ProjectPath("library/std"));
    }
    compiler
  }

  def runTestOnFile(path: String) = {
    // read the file
    var src = cosmo.NodeFs.readFileSync(path, "utf8").asInstanceOf[String]
    var result = compiler.transpile(src)
    if (syntaxOnly) {
      println(result.map(_._2.stgE.module.toDoc.pretty(showDef = true)))
    } else {
      println(result.map(_._1))
    }
  }

  test("Syntax/playground") {
    runTestOnFile("samples/Syntax/playground.cos")
  }
  test("HelloWorld".only) {
    runTestOnFile("samples/HelloWorld/main.cos")
  }
  test("Syntax/literal") {
    runTestOnFile("samples/Syntax/literal.cos")
  }
  test("Syntax/cf.syntax") {
    runTestOnFile("samples/Syntax/cf.syntax.cos")
  }
  test("Syntax/decl.syntax") {
    runTestOnFile("samples/Syntax/decl.syntax.cos")
  }
  test("Syntax/callExpr.syntax") {
    runTestOnFile("samples/Syntax/callExpr.syntax.cos")
  }
  test("Syntax/expr.syntax") {
    runTestOnFile("samples/Syntax/expr.syntax.cos")
  }
  test("Syntax/lambda.syntax") {
    runTestOnFile("samples/Syntax/lambda.syntax.cos")
  }
  test("Syntax/try-catch.syntax") {
    runTestOnFile("samples/Syntax/try-catch.syntax.cos")
  }
  test("Syntax/errs/tmplLit01") {
    runTestOnFile("samples/Syntax/errs/tmplLit01.cos-ast")
  }
  test("Syntax/tmplLit.syntax") {
    runTestOnFile("samples/Syntax/tmplLit.syntax.cos")
  }
  test("TypeAnnotation/add") {
    runTestOnFile("samples/TypeAnnotation/add.cos")
  }
  test("Class/basic") {
    runTestOnFile("samples/Class/basic.cos")
  }
  test("Class/jsonValue") {
    runTestOnFile("samples/Class/jsonValue.cos")
  }
  test("Class/nat") {
    runTestOnFile("samples/Class/nat.cos")
  }
  test("Class/natCons") {
    runTestOnFile("samples/Class/natCons.cos")
  }
  test("Class/method") {
    runTestOnFile("samples/Class/method.cos")
  }
  test("Class/staticMethod") {
    runTestOnFile("samples/Class/staticMethod.cos")
  }
  test("Trait/empty") {
    runTestOnFile("samples/Trait/empty.cos")
  }
  test("Trait/resultProblem") {
    runTestOnFile("samples/Trait/resultProblem.cos")
  }
  test("Trait/formatter") {
    runTestOnFile("samples/Trait/formatter.cos")
  }
  test("Trait/formatter_t") {
    runTestOnFile("samples/Trait/formatter_t.cos")
  }
  test("Trait/display") {
    runTestOnFile("samples/Trait/display.cos")
  }
  test("Trait/mutSelf") {
    runTestOnFile("samples/Trait/mutSelf.cos")
  }
  test("Trait/constraint") {
    runTestOnFile("samples/Trait/constraint.cos")
  }
  test("ControlFlow/loop") {
    runTestOnFile("samples/ControlFlow/loop.cos")
  }
  test("ControlFlow/forIn") {
    runTestOnFile("samples/ControlFlow/forIn.cos")
  }
  test("ControlFlow/mainIf") {
    runTestOnFile("samples/ControlFlow/mainIf.cos")
  }
  test("Format/templateLit") {
    runTestOnFile("samples/Format/templateLit.cos")
  }
  test("Pattern/natAdd") {
    runTestOnFile("samples/Pattern/natAdd.cos")
  }
  test("Pattern/option") {
    runTestOnFile("samples/Pattern/option.cos")
  }
  test("Pattern/result") {
    runTestOnFile("samples/Pattern/result.cos")
  }
  test("Pattern/byStr") {
    runTestOnFile("samples/Pattern/byStr.cos")
  }
  test("Io/readFile") {
    runTestOnFile("samples/Io/readFile.cos")
  }
  test("Vec/push") {
    runTestOnFile("samples/Vec/push.cos")
  }
  test("PythonTutorial/a_calc") {
    runTestOnFile("samples/PythonTutorial/a_calc.cos")
  }
end SampleTest
