import scala.collection.mutable.ArrayBuffer

/** Eight-bit arithmetic whose numerical operations are evaluated as lambda terms. */
object MiniByte {
  sealed trait Term
  case class Var(name: String) extends Term
  case class Lam(name: String, body: Term) extends Term
  case class App(function: Term, argument: Term) extends Term

  private def v(name: String): Term = Var(name)
  private def ref(name: String): Term = v("@" + name)
  private def app(function: Term, arguments: Term*): Term = arguments.foldLeft(function)(App)
  private def lam(names: Seq[String], body: Term): Term = names.foldRight(body)(Lam)
  private def call(name: String, arguments: Term*): Term = app(ref(name), arguments: _*)
  private def let(name: String, value: Term, body: Term): Term = App(Lam(name, body), value)
  def pretty(term: Term): String = term match {
    case Var(name) => name.stripPrefix("@")
    case Lam(name, body) => "(λ" + name + ". " + pretty(body) + ")"
    case App(f, x) => "(" + pretty(f) + " " + pretty(x) + ")"
  }

  object Core {
    private val definitions = ArrayBuffer.empty[(String, Term)]
    private def define(name: String, term: Term): Unit = { definitions += ((name, term)); () }
    private val t = ref("TRUE")
    private val f = ref("FALSE")
    private val identity = ref("I")
    private def bit(width: Int, i: Int, n: Term): Term = call("BIT" + width + "_" + i, n)
    private def pack(width: Int, bits: Seq[Term]): Term = call("PACK" + width, bits: _*)

    define("TRUE", lam(Seq("t", "f"), v("t")))
    define("FALSE", lam(Seq("t", "f"), v("f")))
    define("I", lam(Seq("x"), v("x")))
    define("MARK", lam(Seq("x"), t))
    define("NOT", lam(Seq("p"), app(v("p"), f, t)))
    define("AND", lam(Seq("p", "q"), app(v("p"), v("q"), f)))
    define("OR", lam(Seq("p", "q"), app(v("p"), t, v("q"))))
    define("XOR", lam(Seq("p", "q"), app(v("p"), call("NOT", v("q")), v("q"))))
    define("PAIR", lam(Seq("a", "b", "k"), app(v("k"), v("a"), v("b"))))
    define("FIRST", lam(Seq("p"), app(v("p"), t)))
    define("SECOND", lam(Seq("p"), app(v("p"), f)))
    define("FA", lam(Seq("p", "q", "c", "k"),
      let("u", call("XOR", v("p"), v("q")),
        app(v("k"), call("XOR", v("u"), v("c")),
          call("OR", call("AND", v("p"), v("q")), call("AND", v("u"), v("c")))))))

    // Scala loops build finite syntax trees; the resulting arithmetic is all lambda terms.
    for (width <- Seq(8, 16)) {
      val handlers = (0 until width).reverse.map(i => "f" + i)
      define("ZERO" + width, lam(handlers :+ "x", v("x")))
      for (i <- 0 until width) {
        val probes = (0 until width).reverse.map(j => if (i == j) ref("MARK") else identity)
        define("BIT" + width + "_" + i, lam(Seq("n"), app(v("n"), (probes :+ f): _*)))
      }
      val body = (0 until width).foldLeft[Term](v("x")) { (acc, i) =>
        app(app(v("b" + i), v("f" + i), identity), acc)
      }
      define("PACK" + width, lam((0 until width).map(i => "b" + i) ++ handlers :+ "x", body))
      val shifted = identity +: (1 until width).reverse.map(i => v("f" + i))
      define("SHL" + width, lam(Seq("n") ++ handlers :+ "x", app(v("n"), (shifted :+ v("x")): _*)))
      val result = call("PAIR", pack(width, (0 until width).map(i => v("r" + i))), v("c" + width))
      val ripple = (0 until width).reverse.foldLeft(result) { (next, i) =>
        call("FA", bit(width, i, v("m")), bit(width, i, v("n")), v("c" + i),
          lam(Seq("r" + i, "c" + (i + 1)), next))
      }
      define("ADC" + width, lam(Seq("m", "n", "c0"), ripple))
    }

    define("ADD", lam(Seq("m", "n"), call("ADC8", v("m"), v("n"), f)))
    define("INV8", lam(Seq("n"), pack(8, (0 until 8).map(i => call("NOT", bit(8, i, v("n")))))))
    define("SUB", lam(Seq("m", "n"),
      let("r", call("ADC8", v("m"), call("INV8", v("n")), t),
        call("PAIR", call("FIRST", v("r")), call("NOT", call("SECOND", v("r")))))))
    define("ADD16", lam(Seq("m", "n"), call("FIRST", call("ADC16", v("m"), v("n"), f))))
    define("WIDEN", lam(Seq("n"), pack(16, (0 until 8).map(i => bit(8, i, v("n"))) ++ Seq.fill(8)(f))))
    define("LOW", lam(Seq("n"), pack(8, (0 until 8).map(i => bit(16, i, v("n"))))))
    define("HIGH_NONZERO", lam(Seq("n"), (8 until 16).foldRight(f)((i, rest) =>
      call("OR", bit(16, i, v("n")), rest))))
    val shifts = (0 until 16).scanLeft(v("n"): Term)((prev, _) => call("SHL16", prev)).take(16)
    define("MUL16", lam(Seq("m", "n"), app(v("m"),
      (shifts.reverse.map(n => call("ADD16", n)) :+ ref("ZERO16")): _*)))
    define("MUL", lam(Seq("m", "n"),
      let("p", call("MUL16", call("WIDEN", v("m")), call("WIDEN", v("n"))),
        call("PAIR", call("LOW", v("p")), call("HIGH_NONZERO", v("p"))))))
    val all: Vector[(String, Term)] = definitions.toVector

    /** Literal elaboration is an input boundary, not the arithmetic implementation. */
    def word(number: Int): Term = {
      require(number >= 0 && number <= 255, "a byte is in 0..255")
      val names = (0 until 8).map(i => "s" + i)
      val body = (0 until 8).reverse.foldLeft[Term](v("x")) { (rest, i) =>
        if ((number & (1 << (7 - i))) != 0) App(v(names(i)), rest) else rest
      }
      lam(names :+ "x", body)
    }
  }

  sealed trait Value
  case class Closure(name: String, body: Term, environment: Map[String, Thunk]) extends Value
  case class Neutral(head: String, arguments: Vector[Thunk] = Vector.empty) extends Value
  final class Thunk(compute: () => Value) { lazy val force: Value = compute() }

  /** Lazy closure evaluation and normal-form readback; no numerical instructions. */
  final class Machine {
    private var steps = 0L
    private var fresh = 0
    def reset(): Unit = { steps = 0L; fresh = 0 }
    private def tick(): Unit = {
      steps += 1
      if (steps > 2000000L) throw new IllegalArgumentException("evaluation limit exceeded")
    }
    def saved(term: Term, env: Map[String, Thunk]): Thunk = new Thunk(() => evaluate(term, env))
    private def evaluated(value: Value): Thunk = new Thunk(() => value)
    def evaluate(initial: Term, initialEnv: Map[String, Thunk]): Value = {
      var term = initial
      var env = initialEnv
      while (true) {
        tick()
        term match {
          case Var(name) => return env.get(name).map(_.force).getOrElse(Neutral(name))
          case Lam(name, body) => return Closure(name, body, env)
          case App(fun, arg) => evaluate(fun, env) match {
            case Closure(name, body, captured) => env = captured + (name -> saved(arg, env)); term = body
            case Neutral(head, args) => return Neutral(head, args :+ saved(arg, env))
          }
        }
      }
      throw new AssertionError("unreachable")
    }
    private def quote(value: Value): Term = {
      tick()
      value match {
        case Closure(name, body, env) =>
          val freshName = "$" + fresh
          fresh += 1
          Lam(freshName, quote(evaluate(body, env + (name -> evaluated(Neutral(freshName))))))
        case Neutral(head, args) => args.foldLeft[Term](Var(head))((rest, arg) => App(rest, quote(arg.force)))
      }
    }
    def normalize(term: Term, env: Map[String, Thunk]): Term = quote(evaluate(term, env))
  }

  /** Decode and rename the computed normal form, without recreating it from a native sum. */
  def decodeWord(term: Term): (Int, String) = {
    var body = term
    val names = ArrayBuffer.empty[String]
    for (_ <- 0 to 8) body match {
      case Lam(name, rest) => names += name; body = rest
      case _ => throw new AssertionError("not a byte numeral")
    }
    require(names.distinct.size == 9)
    val renaming = names.take(8).zipWithIndex.map { case (name, i) => name -> ("s" + i) }.toMap + (names.last -> "x")
    def render(rest: Term): String = rest match {
      case Var(name) => renaming(name)
      case App(Var(name), arg: Var) => renaming(name) + " " + render(arg)
      case App(Var(name), arg) => renaming(name) + " (" + render(arg) + ")"
      case _ => throw new AssertionError("malformed byte body")
    }
    val text = (0 until 8).map(i => "λs" + i + ".").mkString + "λx. " + render(body)
    var byte = 0
    var previous = -1
    while (body != Var(names.last)) body match {
      case App(Var(name), rest) =>
        val i = names.take(8).indexOf(name)
        require(i >= 0 && i > previous, "noncanonical numeral")
        byte |= 1 << (7 - i)
        previous = i
        body = rest
      case _ => throw new AssertionError("malformed byte body")
    }
    (byte, text)
  }
  def decodeBoolean(term: Term): Boolean = term match {
    case Lam(t, Lam(f, Var(x))) if t != f && (x == t || x == f) => x == t
    case _ => throw new AssertionError("not a Church Boolean")
  }
  case class Result(byte: Int, lambda: String, fault: Boolean) {
    def line: String = lambda + " 0b" + Integer.toBinaryString(byte) + " " + byte
  }
  final class Session {
    private val machine = new Machine
    private val env = Core.all.foldLeft(Map.empty[String, Thunk]) { case (captured, (name, term)) =>
      captured + ("@" + name -> machine.saved(term, captured))
    }
    def normalize(term: Term): Term = { machine.reset(); machine.normalize(term, env) }
    def compute(a: Int, op: String, b: Int): Result = {
      val name = op match {
        case "+" => "ADD"
        case "-" => "SUB"
        case "*" => "MUL"
        case _ => throw new IllegalArgumentException("operator must be +, -, or *; quote '*' in your shell")
      }
      normalize(call(name, Core.word(a), Core.word(b))) match {
        case Lam(k, App(App(Var(head), word), fault)) if k == head =>
          val (byte, text) = decodeWord(word)
          Result(byte, text, decodeBoolean(fault))
        case _ => throw new AssertionError("not a result pair")
      }
    }
  }

  def parseNumber(text: String): Int = {
    if (text.startsWith("0x") || text.startsWith("0X"))
      throw new IllegalArgumentException("use 0b for binary, for example 0b110; 0x conventionally means hexadecimal")
    val number = if (text.matches("[0-9]+")) BigInt(text, 10)
      else if (text.matches("0[bB][01]+")) BigInt(text.drop(2), 2)
      else throw new IllegalArgumentException("numbers must be unsigned decimal or binary with a 0b prefix")
    if (number > 255) throw new IllegalArgumentException("input is outside the eight-bit range 0..255")
    number.toInt
  }
  private val usage = "Usage: ./minibyte NUMBER OP NUMBER\n" +
    "Numbers: decimal 0..255 or binary 0b0..0b11111111. Operators: + - *\n" +
    "Examples: ./minibyte 6 + 7   |   ./minibyte 0b110 + 0b111   |   ./minibyte 6 '*' 7\n" +
    "Results wrap modulo 256; unsigned overflow/underflow notices go to stderr.\n" +
    "Use --defs to print the lambda definitions."
  def main(args: Array[String]): Unit = {
    try {
      if (args.toVector == Vector("--help")) println(usage)
      else if (args.toVector == Vector("--defs")) Core.all.foreach { case (name, term) => println(name + " := " + pretty(term)) }
      else {
        if (args.length != 3) throw new IllegalArgumentException(usage)
        val result = new Session().compute(parseNumber(args(0)), args(1), parseNumber(args(2)))
        println(result.line)
        if (result.fault) Console.err.println((if (args(1) == "-") "underflow" else "overflow") + ": result wraps modulo 256")
      }
    } catch {
      case e: IllegalArgumentException => Console.err.println("error: " + e.getMessage); sys.exit(1)
      case _: StackOverflowError => Console.err.println("error: evaluator stack exhausted"); sys.exit(1)
    }
  }
}
