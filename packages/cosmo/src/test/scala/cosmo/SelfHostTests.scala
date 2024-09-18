package cosmo
import scala.scalajs.js

class SelfHostTest extends munit.FunSuite:
  def runTestOnFile(path: String) = {
    var compiler = new Cosmo();
    compiler.loadPackage(PackageMetaSource.ProjectPath("library/std"));
    compiler.preloadPackage("std");

    val prog = compiler.getExecutable(path);
    NodeChildProcess.execSync(prog, js.Dynamic.literal(stdio = "inherit"));
  }

  test("parser") {
    runTestOnFile("packages/cosmoc/src/parser.cos")
  }
end SelfHostTest
