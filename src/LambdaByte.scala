import scala.collection.mutable.ArrayBuffer
import scala.io.{Source, StdIn}

/** LambdaByte: a lambda-calculus language with checked eight-bit arithmetic. */
object LambdaByte {
  sealed trait Term
  final case class Var(name: String) extends Term
  final case class Lam(name: String, body: Term) extends Term
  final case class App(function: Term, argument: Term) extends Term

  def v(name: String): Term = Var(name)
  def ref(name: String): Term = Var("@" + name) // inaccessible to source identifiers
  def ap(function: Term, arguments: Term*): Term = arguments.foldLeft(function)(App)
  def lam(names: Seq[String], body: Term): Term = names.foldRight(body)(Lam)
  def let(name: String, value: Term, body: Term): Term = App(Lam(name, body), value)
  def call(name: String, arguments: Term*): Term = ap(ref(name), arguments: _*)

  def pretty(term: Term): String = term match {
    case Var(name) => name.stripPrefix("@")
    case Lam(name, body) => "(λ" + name + ". " + pretty(body) + ")"
    case App(function, argument) => "(" + pretty(function) + " " + pretty(argument) + ")"
  }

  object Core {
    private val definitions = ArrayBuffer.empty[(String, Term)]
    private def define(name: String, term: Term): Unit = { definitions += ((name, term)); () }
    private val T = ref("TRUE")
    private val F = ref("FALSE")
    private val I = ref("I")
    private def and(terms: Seq[Term]): Term = terms.foldRight(T)((a, b) => call("AND", a, b))
    private def or(terms: Seq[Term]): Term = terms.foldRight(F)((a, b) => call("OR", a, b))
    private def bit(width: Int, index: Int, number: Term): Term = call("B" + width + "_" + index, number)
    private def pack(width: Int, bits: Seq[Term]): Term = call("PACK" + width, bits: _*)

    define("TRUE", lam(Seq("t", "f"), v("t")))
    define("FALSE", lam(Seq("t", "f"), v("f")))
    define("I", lam(Seq("x"), v("x")))
    define("MARK", lam(Seq("x"), T))
    define("NOT", lam(Seq("p"), ap(v("p"), F, T)))
    define("AND", lam(Seq("p", "q"), ap(v("p"), v("q"), F)))
    define("OR", lam(Seq("p", "q"), ap(v("p"), T, v("q"))))
    define("XOR", lam(Seq("p", "q"), ap(v("p"), call("NOT", v("q")), v("q"))))
    define("RESULT", lam(Seq("word", "u", "s", "k"), ap(v("k"), v("word"), v("u"), v("s"))))
    define("VALUE", lam(Seq("r"), ap(v("r"), lam(Seq("word", "u", "s"), v("word")))))
    define("UF", lam(Seq("r"), ap(v("r"), lam(Seq("word", "u", "s"), v("u")))))
    define("SF", lam(Seq("r"), ap(v("r"), lam(Seq("word", "u", "s"), v("s")))))
    define("FA", lam(Seq("p", "q", "c", "k"),
      let("t", call("XOR", v("p"), v("q")),
        ap(v("k"), call("XOR", v("t"), v("c")),
          call("OR", call("AND", v("p"), v("q")), call("AND", v("t"), v("c")))))))

    // These loops construct finite lambda ASTs. They do not calculate source arithmetic.
    for (width <- Seq(8, 16)) {
      val handlers = (0 until width).reverse.map(i => "f" + i)
      define("ZERO" + width, lam(handlers :+ "x", v("x")))
      for (index <- 0 until width) {
        val arguments = (0 until width).reverse.map(i => if (i == index) ref("MARK") else I)
        define("B" + width + "_" + index, lam(Seq("n"), ap(v("n"), (arguments :+ F): _*)))
      }
      val bitNames = (0 until width).map(i => "b" + i)
      val body = (0 until width).foldLeft[Term](v("x")) { (accumulator, i) =>
        ap(ap(v("b" + i), v("f" + i), I), accumulator)
      }
      define("PACK" + width, lam(bitNames ++ handlers :+ "x", body))
      val shiftArguments = I +: (1 until width).reverse.map(i => v("f" + i))
      define("SHL" + width, lam(Seq("n") ++ handlers :+ "x", ap(v("n"), (shiftArguments :+ v("x")): _*)))
      val sums = (0 until width).map(i => v("s" + i))
      val output = if (width == 8)
        call("RESULT", pack(width, sums), v("c8"), call("XOR", v("c7"), v("c8")))
      else pack(width, sums)
      val ripple = (0 until width).reverse.foldLeft(output) { (next, i) =>
        call("FA", bit(width, i, v("m")), bit(width, i, v("n")), v("c" + i),
          lam(Seq("s" + i, "c" + (i + 1)), next))
      }
      define("ADC" + width, lam(Seq("m", "n", "c0"), ripple))
    }

    define("INV8", lam(Seq("n"), pack(8, (0 until 8).map(i => call("NOT", bit(8, i, v("n")))))))
    define("ADD_RAW", lam(Seq("m", "n"), call("ADC8", v("m"), v("n"), F)))
    define("SUB_WORD", lam(Seq("m", "n"), call("VALUE", call("ADC8", v("m"), call("INV8", v("n")), T))))
    define("SUB_RAW", lam(Seq("m", "n"),
      let("r", call("ADC8", v("m"), call("INV8", v("n")), T),
        call("RESULT", call("VALUE", v("r")), call("NOT", call("UF", v("r"))), call("SF", v("r"))))))
    define("ADD16", lam(Seq("m", "n"), call("ADC16", v("m"), v("n"), F)))
    val shifts16 = (0 until 16).scanLeft(v("n"): Term)((previous, _) => call("SHL16", previous)).take(16)
    define("MUL16", lam(Seq("m", "n"), ap(v("m"),
      (shifts16.reverse.map(shift => call("ADD16", shift)) :+ ref("ZERO16")): _*)))
    define("WIDEN8", lam(Seq("n"), pack(16, (0 until 8).map(i => bit(8, i, v("n"))) ++ Seq.fill(8)(F))))
    define("LOW8", lam(Seq("n"), pack(8, (0 until 8).map(i => bit(16, i, v("n"))))))
    define("HIGH8", lam(Seq("n"), pack(8, (8 until 16).map(i => bit(16, i, v("n"))))))
    define("NONZERO8", lam(Seq("n"), or((0 until 8).map(i => bit(8, i, v("n"))))))
    define("EQ8", lam(Seq("m", "n"), call("NOT", or((0 until 8).map(i =>
      call("XOR", bit(8, i, v("m")), bit(8, i, v("n"))))))))
    define("SIGNED_FIT", lam(Seq("lo", "hi"), call("NOT", or((0 until 8).map(i =>
      call("XOR", bit(8, i, v("hi")), bit(8, 7, v("lo"))))))))
    val correctedHigh = call("SUB_WORD",
      call("SUB_WORD", v("hi"), ap(bit(8, 7, v("m")), v("n"), ref("ZERO8"))),
      ap(bit(8, 7, v("n")), v("m"), ref("ZERO8")))
    define("MUL_RAW", lam(Seq("m", "n"),
      let("p", call("MUL16", call("WIDEN8", v("m")), call("WIDEN8", v("n"))),
        let("lo", call("LOW8", v("p")),
          let("hi", call("HIGH8", v("p")),
            let("hs", correctedHigh,
              call("RESULT", v("lo"), call("NONZERO8", v("hi")),
                call("NOT", call("SIGNED_FIT", v("lo"), v("hs"))))))))))
    for (operation <- Seq("ADD", "SUB", "MUL")) {
      define(operation + "_LIFT", lam(Seq("a", "b"),
        let("r", call(operation + "_RAW", call("VALUE", v("a")), call("VALUE", v("b"))),
          call("RESULT", call("VALUE", v("r")),
            or(Seq(call("UF", v("a")), call("UF", v("b")), call("UF", v("r")))),
            or(Seq(call("SF", v("a")), call("SF", v("b")), call("SF", v("r"))))))))
    }
    define("ZERO_Q", lam(Seq("a"), call("NOT", call("NONZERO8", call("VALUE", v("a"))))))
    define("EQ_Q", lam(Seq("a", "b"), call("EQ8", call("VALUE", v("a")), call("VALUE", v("b")))))
    define("HAS_FAULT", lam(Seq("a"), call("OR", call("UF", v("a")), call("SF", v("a")))))
    val self = lam(Seq("x"), ap(v("f"), ap(v("x"), v("x"))))
    define("FIX", lam(Seq("f"), ap(self, self)))
    val all: Vector[(String, Term)] = definitions.toVector
    val aliases: Map[String, String] = Map("true" -> "TRUE", "false" -> "FALSE", "is_zero" -> "ZERO_Q",
      "eq" -> "EQ_Q", "has_fault" -> "HAS_FAULT", "unsigned_fault" -> "UF", "signed_overflow" -> "SF", "fix" -> "FIX")

    /** Native bits are used only to elaborate an input literal into its lambda AST. */
    def word(number: Int, width: Int = 8): Term = {
      val handlers = (0 until width).reverse.map(i => "f" + i)
      val body = (0 until width).foldLeft[Term](v("x")) { (accumulator, i) =>
        if ((number & (1 << i)) != 0) App(v("f" + i), accumulator) else accumulator
      }
      lam(handlers :+ "x", body)
    }
    def literal(number: Int): Term = call("RESULT", word(number & 255), F, F)
  }

  final class LanguageError(message: String) extends RuntimeException(message)
  sealed trait Value
  final case class Closure(name: String, body: Term, environment: Map[String, Thunk]) extends Value
  final case class Neutral(head: String, arguments: Vector[Thunk] = Vector.empty) extends Value
  final class Thunk(computation: () => Value) {
    private var cached: Option[Value] = None
    private var running = false
    def force: Value = cached match {
      case Some(value) => value
      case None =>
        if (running) throw new LanguageError("evaluation entered a cyclic demand (probably divergent)")
        running = true
        try { val result = computation(); cached = Some(result); result }
        finally { running = false }
    }
  }

  /** A closure-based, call-by-need lambda evaluator. No arithmetic instructions. */
  final class Machine(val limit: Long = 2000000L) {
    var steps: Long = 0L
    private var fresh = 0
    def reset(): Unit = { steps = 0L; fresh = 0 }
    private def tick(): Unit = {
      steps += 1L
      if (steps > limit) throw new LanguageError("evaluation limit exceeded; try --fuel with a larger limit")
    }
    def saved(term: Term, environment: Map[String, Thunk]): Thunk = new Thunk(() => evaluate(term, environment))
    def evaluated(value: Value): Thunk = new Thunk(() => value)
    def evaluate(initial: Term, initialEnvironment: Map[String, Thunk]): Value = {
      var term = initial
      var environment = initialEnvironment
      while (true) {
        tick()
        term match {
          case Var(name) => return environment.get(name).map(_.force).getOrElse(Neutral(name))
          case Lam(name, body) => return Closure(name, body, environment)
          case App(function, argument) =>
            val value = evaluate(function, environment)
            val thunk = saved(argument, environment)
            value match {
              case Closure(name, body, captured) => term = body; environment = captured + (name -> thunk)
              case Neutral(head, arguments) => return Neutral(head, arguments :+ thunk)
            }
        }
      }
      throw new AssertionError("unreachable")
    }
    def applyValue(function: Value, argument: Value): Value = function match {
      case Closure(name, body, environment) => evaluate(body, environment + (name -> evaluated(argument)))
      case Neutral(head, arguments) => Neutral(head, arguments :+ evaluated(argument))
    }
    def quote(value: Value): Term = {
      tick()
      value match {
        case closure: Closure =>
          // '$' cannot occur in a source identifier, so readback cannot capture it.
          val name = "$" + fresh
          fresh += 1
          Lam(name, quote(applyValue(closure, Neutral(name))))
        case Neutral(head, arguments) => arguments.foldLeft[Term](Var(head)) { (accumulator, argument) =>
          App(accumulator, quote(argument.force))
        }
      }
    }
    def normalize(term: Term, environment: Map[String, Thunk]): Term = quote(evaluate(term, environment))
  }

  final case class Packet(byte: Int, unsignedFault: Boolean, signedOverflow: Boolean) {
    def signed: Int = if (byte < 128) byte else byte - 256
    def render: String = {
      val bits = ("00000000" + Integer.toBinaryString(byte)).takeRight(8)
      byte + " [" + bits + "] signed=" + signed + " | unsigned_fault=" + unsignedFault +
        " | signed_overflow=" + signedOverflow
    }
  }
  def decodeBoolean(term: Term): Option[Boolean] = term match {
    case Lam(t, Lam(f, Var(result))) if t != f && result == t => Some(true)
    case Lam(t, Lam(f, Var(result))) if t != f && result == f => Some(false)
    case _ => None
  }
  /** Structural output decoding, performed only after lambda normalization. */
  def decodeWord(term: Term, width: Int = 8): Option[Int] = {
    var body = term
    val names = ArrayBuffer.empty[String]
    for (_ <- 0 to width) body match {
      case Lam(name, rest) => names += name; body = rest
      case _ => return None
    }
    if (names.distinct.length != width + 1) return None
    var total = 0
    var previous = width
    while (body != Var(names.last)) body match {
      case App(Var(name), rest) =>
        val position = names.take(width).indexOf(name)
        val bit = width - 1 - position
        if (position < 0 || bit >= previous) return None
        total |= 1 << bit
        previous = bit
        body = rest
      case _ => return None
    }
    Some(total)
  }
  def decodePacket(term: Term): Option[Packet] = term match {
    case Lam(k, App(App(App(Var(head), word), u), s)) if k == head =>
      for (byte <- decodeWord(word); unsigned <- decodeBoolean(u); signed <- decodeBoolean(s))
        yield Packet(byte, unsigned, signed)
    case _ => None
  }
  def freeVariables(term: Term): Set[String] = term match {
    case Var(name) => Set(name)
    case Lam(name, body) => freeVariables(body) - name
    case App(function, argument) => freeVariables(function) ++ freeVariables(argument)
  }

  final case class Token(text: String, offset: Int)
  object Lexer {
    def tokens(source: String): Vector[Token] = {
      val output = ArrayBuffer.empty[Token]
      var i = 0
      while (i < source.length) {
        val c = source.charAt(i)
        if (c.isWhitespace) i += 1
        else if (c == '#' || (c == '/' && i + 1 < source.length && source.charAt(i + 1) == '/')) {
          while (i < source.length && source.charAt(i) != '\n') i += 1
        } else if (c.isLetter || c == '_') {
          val start = i
          i += 1
          while (i < source.length && (source.charAt(i).isLetterOrDigit || source.charAt(i) == '_')) i += 1
          output += Token(source.substring(start, i), start)
        } else if (c.isDigit) {
          val start = i
          i += 1
          if (c == '0' && i < source.length && (source.charAt(i) == 'b' || source.charAt(i) == 'B')) {
            i += 1
            while (i < source.length && (source.charAt(i) == '0' || source.charAt(i) == '1')) i += 1
          } else while (i < source.length && source.charAt(i).isDigit) i += 1
          output += Token(source.substring(start, i), start)
        } else if (source.startsWith("->", i)) { output += Token("->", i); i += 2 }
        else if ("()=;+-*".contains(c)) { output += Token(c.toString, i); i += 1 }
        else throw new LanguageError("unexpected character '" + c + "' at offset " + i)
      }
      (output :+ Token("<eof>", source.length)).toVector
    }
  }
  sealed trait Statement
  final case class Bind(name: String, expression: Term) extends Statement
  final case class Print(expression: Term) extends Statement
  final class Parser(source: String) {
    private val tokens = Lexer.tokens(source)
    private var position = 0
    private val keywords = Set("let", "rec", "in", "print", "fun", "if", "then", "else")
    private def current: String = tokens(position).text
    private def take(): String = { val text = current; position += 1; text }
    private def accept(text: String): Boolean = if (current == text) { take(); true } else false
    private def fail(message: String): Nothing = throw new LanguageError(message + " at offset " + tokens(position).offset)
    private def expect(text: String): Unit = if (!accept(text)) fail("expected '" + text + "', found '" + current + "'")
    private def identifier(): String = {
      val name = current
      if (name.isEmpty || !(name.head.isLetter || name.head == '_') || keywords(name)) fail("expected an identifier")
      take()
    }
    def program(): Vector[Statement] = {
      val statements = ArrayBuffer.empty[Statement]
      while (current != "<eof>") {
        if (accept(";")) ()
        else {
          val statement = if (accept("let")) {
            val binding = definition(); Bind(binding._1, binding._2)
          } else { accept("print"); Print(expression()) }
          statements += statement
          if (current != "<eof>") expect(";")
        }
      }
      statements.toVector
    }
    def single(): Term = { val result = expression(); accept(";"); expect("<eof>"); result }
    private def definition(): (String, Term) = {
      val recursive = accept("rec")
      val name = identifier()
      val parameters = ArrayBuffer.empty[String]
      while (current != "=") parameters += identifier()
      expect("=")
      val body = lam(parameters.toVector, expression())
      val value = if (recursive) call("FIX", Lam(name, body)) else body
      (name, value)
    }
    private def expression(): Term = {
      if (accept("let")) {
        val binding = definition(); expect("in")
        let(binding._1, binding._2, expression())
      } else if (accept("fun")) {
        val parameters = ArrayBuffer(identifier())
        while (current != "->") parameters += identifier()
        expect("->"); lam(parameters.toVector, expression())
      }
      else if (accept("if")) {
        val condition = expression(); expect("then"); val yes = expression(); expect("else")
        ap(condition, yes, expression())
      } else sum()
    }
    private def sum(): Term = {
      var result = product()
      while (current == "+" || current == "-") {
        val operation = take()
        val core = if (operation == "+") "ADD_LIFT" else "SUB_LIFT"
        result = call(core, result, product())
      }
      result
    }
    private def product(): Term = {
      var result = application()
      while (current == "*") { take(); result = call("MUL_LIFT", result, application()) }
      result
    }
    private def application(): Term = {
      var result = atom()
      while (accept("(")) { val argument = expression(); expect(")"); result = App(result, argument) }
      result
    }
    private def readLiteral(negative: Boolean): Term = {
      val token = take()
      val value = try {
        if (token.startsWith("0b") || token.startsWith("0B")) BigInt(token.drop(2), 2) else BigInt(token)
      } catch { case _: NumberFormatException => fail("invalid literal '" + token + "'") }
      val maximum = if (negative) BigInt(128) else BigInt(255)
      if (value < 0 || value > maximum) fail(if (negative) "negative literal must be in -128..0" else "literal must be in 0..255")
      Core.literal(if (negative) -value.toInt else value.toInt)
    }
    private def atom(): Term = {
      if (accept("(")) { val result = expression(); expect(")"); result }
      else if (accept("-")) {
        if (current.headOption.exists(_.isDigit)) readLiteral(true)
        else call("SUB_LIFT", Core.literal(0), application())
      } else if (current.headOption.exists(_.isDigit)) readLiteral(false)
      else if (current == "let" || current == "fun" || current == "if" || current == "<eof>")
        fail("expected an expression (parenthesize local bindings, functions and conditionals here)")
      else Var(identifier())
    }
  }

  final class Session(fuel: Long = 2000000L) {
    val machine = new Machine(fuel)
    var environment: Map[String, Thunk] = Map.empty
    for ((name, definition) <- Core.all) {
      val captured = environment
      environment += ("@" + name -> machine.saved(definition, captured))
    }
    for ((name, target) <- Core.aliases) environment += (name -> environment("@" + target))
    private def validate(term: Term): Unit = {
      val unknown = freeVariables(term) -- environment.keySet
      if (unknown.nonEmpty) throw new LanguageError("unknown identifier(s): " + unknown.toSeq.sorted.mkString(", "))
    }
    def evaluate(term: Term): Term = {
      machine.reset(); validate(term)
      try machine.normalize(term, environment)
      catch { case _: StackOverflowError => throw new LanguageError("evaluation stack exhausted (deep recursion or divergence)") }
    }
    def value(expression: String): Term = evaluate(new Parser(expression).single())
    def execute(source: String): Vector[String] = new Parser(source).program().map {
      case Bind(name, expression) =>
        machine.reset(); validate(expression)
        val result = try machine.evaluate(expression, environment)
        catch { case _: StackOverflowError => throw new LanguageError("evaluation stack exhausted") }
        environment += (name -> machine.evaluated(result))
        "defined " + name
      case Print(expression) =>
        val normalized = evaluate(expression)
        decodePacket(normalized).map(_.render).orElse(decodeBoolean(normalized).map(b => if (b) "true" else "false"))
          .getOrElse(pretty(normalized))
    }
  }

  val help: String = """LambdaByte: eight-bit arithmetic in lambda calculus.
let x = 200;                       bind a value
print x + 100;                     addition
print 2 - 5;                       subtraction
print 13 * 7;                      multiplication
let f = fun x -> x + 1;             anonymous function
let f x = x + 1;                   function-binding shorthand
print f(41);                       function application
print if is_zero(0) then 7 else 9;  lazy conditional
print let x = 2 in x + 3;           local binding
let rec fact n = ...;              recursive binding
true / false                       Church Booleans
is_zero(x), eq(x)(y)                predicates
has_fault(x), unsigned_fault(x), signed_overflow(x)   flags
fix(f)                             lazy fixed-point combinator
:lambda EXPR                       show the normalized lambda term
:defs                              show every core definition
:help, :quit                       REPL commands
Words wrap modulo 256. Flags are printed as true or false.
unsigned_fault = carry/borrow/truncation; signed_overflow = signed overflow.
Flags stay set through subsequent arithmetic. Both numeric views are printed.
"""
  def main(arguments: Array[String]): Unit = {
    var fuel = 2000000L
    var command = "repl"
    var argument = ""
    var index = 0
    def next(): String = {
      index += 1
      if (index >= arguments.length) throw new LanguageError("missing command argument")
      arguments(index)
    }
    try {
      while (index < arguments.length) {
        arguments(index) match {
          case "--fuel" => fuel = next().toLong; if (fuel <= 0) throw new LanguageError("fuel must be positive")
          case "--expr" => command = "expr"; argument = next()
          case "--lambda" => command = "lambda"; argument = next()
          case "--defs" => command = "defs"
          case "--help" | "-h" => command = "help"
          case option if option.startsWith("-") => throw new LanguageError("unknown option: " + option)
          case path => if (command != "repl") throw new LanguageError("choose one file or command"); command = "file"; argument = path
        }
        index += 1
      }
      val session = new Session(fuel)
      def definitions(): Unit = Core.all.foreach { case (name, term) => println(name + " := " + pretty(term)) }
      def run(source: String): Unit = session.execute(source).foreach(println)
      command match {
        case "help" => println(help)
        case "defs" => definitions()
        case "expr" => run(argument)
        case "lambda" => println(pretty(session.value(argument)))
        case "file" =>
          val source = Source.fromFile(argument, "UTF-8")
          try run(source.mkString) finally source.close()
        case _ =>
          println(help)
          var active = true
          while (active) {
            val line = StdIn.readLine("lambda> ")
            if (line == null || line.trim == ":quit" || line.trim == ":q") active = false
            else try {
              if (line.trim == ":help") println(help)
              else if (line.trim == ":defs") definitions()
              else if (line.trim.startsWith(":lambda ")) println(pretty(session.value(line.trim.drop(8))))
              else if (line.trim.nonEmpty) run(line)
            } catch { case error: LanguageError => println("error: " + error.getMessage) }
          }
      }
    } catch {
      case error: LanguageError => Console.err.println("error: " + error.getMessage); sys.exit(1)
      case error: NumberFormatException => Console.err.println("error: invalid numeric command argument"); sys.exit(1)
      case error: java.io.IOException => Console.err.println("error: " + error.getMessage); sys.exit(1)
    }
  }
}
