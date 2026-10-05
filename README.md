# LambdaByte

**Eight-bit arithmetic in lambda calculus.**

A small language written in Scala. Its arithmetic builds Boolean gates, ripple-carry
adders, and a sixteen-bit multiplier out of lambda terms, solely to calculate things
like `2 + 3`. The numbers retain the proposed eight-handler binary encoding.

## Run immediately

The included executable JAR contains the Scala runtime. You need Java 17 or newer;
you do not need to install Scala or download any dependencies to run it.

After downloading or cloning the repository, enable the helper scripts once:

```sh
chmod +x build.sh run.sh test.sh
```

```sh
java -jar lambdabyte.jar                  # interactive REPL
java -jar lambdabyte.jar examples/demo.lb
java -jar lambdabyte.jar examples/factorial.lb
java -jar lambdabyte.jar --expr 'print 13 * 7;'
java -jar lambdabyte.jar --lambda '1 + 2'
java -jar lambdabyte.jar --defs           # every actual core lambda term
```

Or use `./run.sh`. Every REPL input is one line; use a file for multiline programs.
Statements use semicolons, but the last semicolon is optional. EOF or `:quit` exits.

## Syntax

```text
let x = 200;
print x + 100;                 # addition: 44, unsigned overflow
print 2 - 5;                  # subtraction: 253, signed view -3
print 13 * 7;                   # multiplication: 91

let increment = fun n -> n + 1;
print increment(41);              # 42
print if is_zero(0) then 7 else 9; # 7

let rec fact n = if is_zero(n) then 1 else n * fact(n - 1);
print fact(5);                   # 120
```

| Syntax | Meaning |
| --- | --- |
| `let x = e;` | Bind `x` to `e`, with lexical scope |
| `print e;` or `e;` | Evaluate and print |
| `a + b` | Add |
| `a - b` | Subtract |
| `a * b` | Multiply |
| `fun x -> e` | Lambda abstraction |
| `fun x y -> e` | Curried abstraction with several parameters |
| `let f x = e;` | Function-binding shorthand |
| `let rec f x = e;` | Recursive binding using `FIX` |
| `let x = e in body` | Local binding expression |
| `f(e)` | Function application; `f(a)(b)` is curried application |
| `if p then a else b` | Lazy conditional using a Church Boolean |
| `true`, `false` | Church true and false |
| `is_zero(x)`, `eq(x)(y)` | Zero and equality tests |
| `unsigned_fault(x)`, `signed_overflow(x)`, `has_fault(x)` | Unsigned fault, signed overflow, either flag |
| `fix(f)` | Lazy fixed-point combinator for recursion |

`*` has higher precedence than `+` and `-`. Addition and subtraction
associate left. Functions and conditionals used inside other expressions need
parentheses. Local bindings also need parentheses when used as operands.
Both `#` and `//` start a line comment.

Literals accept decimal `0..255`, binary such as `0b1101`, and negative
two's-complement values `-128..-1`. A negative literal starts with clear flags;
the expression `0 - 3` performs subtraction and therefore sets the unsigned
borrow flag. Unary `-e` is subtraction from zero, except for negative literals.

## Reading the result

```text
0 [00000000] signed=0 | unsigned_fault=true | signed_overflow=false
```

Every number prints its unsigned byte, eight bits, signed two's-complement view,
and two Boolean flags.

- **Unsigned fault:** addition carry, subtraction borrow, or lost high product bits.
- **Signed overflow:** the exact operation on signed views does not fit `-128..127`.
- The stored result always wraps modulo 256. Detection reports a flag; it does not
  trap, saturate, or widen the returned value.
- Flags propagate through arithmetic operands and remain set. A binding preserves
  them. Tests return plain Booleans; their input history is not automatically
  attached to a subsequently chosen branch. Unchosen branches do not execute.

Examples:

| Expression | Byte | Signed view | Unsigned fault | Signed overflow |
| --- | ---: | ---: | --- | --- |
| `255 + 1` | 0 | 0 | true | false |
| `127 + 1` | 128 | -128 | false | true |
| `2 - 5` | 253 | -3 | true | false |
| `16 * 16` | 0 | 0 | true | true |
| `-1 * 1` | 255 | -1 | false | false |
| `-1 * -1` | 1 | 1 | true | false |

The last line has unsigned overflow because the raw bytes are `255 * 255`;
the signed calculation is `-1 * -1 = 1`, which fits. Both views are deliberately
reported rather than silently selecting one interpretation.

## Where the lambda calculus lives

`src/LambdaByte.scala` has three separate responsibilities:

1. `Core` constructs lambda ASTs using only `Var`, `Lam`, and `App`.
2. `Parser` translates the surface syntax into those ASTs.
3. `Machine` evaluates closures with call-by-need and reconstructs a normal form.

Arithmetic and flag detection are lambda terms. Scala integers are used for input
literal elaboration, AST construction indices, evaluation bookkeeping, output
decoding, and test expectations. There is no native integer-add/multiply shortcut
in the arithmetic evaluator. `core.lambda` exports all actual definitions.

Your number one is still, up to variable renaming:

```text
λa.λb.λc.λd.λe.λf.λg.λh.λx. h x
```

Two uses `g x`; three uses `g (h x)`. This is a binary selection encoding, rather
than the usual unary Church numeral encoding.

General functions and application remain available, so the language can express
arbitrary untyped lambda terms, including fixed points. The eight-bit arithmetic
library does not restrict the rest of the language to a finite-state calculator.

## Build from source

There are no third-party application dependencies or build plugins. With an
installed Scala compiler:

```sh
./build.sh
scala -cp build/classes LambdaByte examples/demo.lb
scala -cp build/classes LambdaByteTests
scala -cp build/classes LambdaByteTests --exhaustive
```

The source and included JAR were compiled and tested with Scala 2.11.12 on Java
17. They use conventional Scala 2 syntax. Alternatively, a current Scala CLI can
run the source with Scala 2.13.18:

```sh
scala-cli run src --scala 2.13.18 --main-class LambdaByte -- examples/demo.lb
```

The alternate compiler version is a convenience command, not a claimed tested
configuration. Scala installation: https://docs.scala-lang.org/getting-started/install-scala.html

## Verify

```sh
./test.sh                # boundary tests plus parser, scope, recursion, laziness
./test.sh --exhaustive   # all 65,536 byte pairs per operation, with both flags
```

The saved test output is in `validation.txt`. The lambda arithmetic definitions
match the exhaustively verified core; the updated syntax passed 3,209 checks. `--fuel N` changes the per-statement
evaluation budget; the default is 2,000,000 evaluator/readback transitions.

## Limits

- Eight-bit results only; no division, arbitrary-precision arithmetic, strings,
  mutable state, or file I/O inside the language.
- The lambda calculus is untyped. Arithmetic operators require the canonical
  encoded numeric results; arbitrary functions are not validated numeric operands.
  Misuse can produce another lambda term or exhaust evaluator resources.
- A lambda term can diverge. Fuel, JVM stack, and memory are practical limits.
  Full normal-form printing can demand a function body that would otherwise stay
  unevaluated.
- `let` is not implicitly recursive. Use `let rec` or `fix` for recursion. Later rebinding does
  not change values already captured by an earlier function.
- Flags record arithmetic history, not a source-location trace. Predicates and
  branch selection do not propagate predicate history automatically.

## Files

- `src/LambdaByte.scala`: complete implementation.
- `src/LambdaByteTests.scala`: independent arithmetic oracle and language tests.
- `report.pdf` / `report.md`: syntax, definitions, semantics, proof sketches, limits.
- `core.lambda`: all finite core definitions, exported by the executable.
- `SUBMISSION.md`: a short contest-board description to adapt.
- `examples/`: working programs.
- `lambdabyte.jar`: executable including the Scala standard library.
- `SCALA-LICENSE.txt`: bundled runtime licensing and attribution.

## Why this is beautiful pretension

A conventional program would add two integers. This one reconstructs bits by
probing higher-order functions, assembles a ripple-carry circuit, widens products
to sixteen bits, reconstructs signed overflow, and finally reports two Boolean flags.
Every layer has a purpose; the disproportionate effort is the point of the contest.
