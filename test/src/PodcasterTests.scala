package podcaster
import utest._
object PodcasterTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val result = "<h1>hello</h1>"
      assert(result == "<h1>hello</h1>")
      result
    }
    test("escaping") {
      val result = "<h1>&lt;hello&gt;</h1>"
      assert(result == "<h1>&lt;hello&gt;</h1>")
      result
    }
  }
}
