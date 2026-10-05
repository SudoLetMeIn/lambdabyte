# MiniByte

A small eight-bit calculator written in **Scala**. Addition, subtraction,
multiplication, and unsigned overflow/underflow detection are implemented as lambda
terms. It accepts exactly two unsigned numbers and one operator.

## Run

The included JAR contains the Scala standard library. Java 17 is sufficient to
run it; installing a Scala compiler is only necessary to rebuild the source.

```sh
chmod +x minibyte test.sh build.sh
./minibyte 6 + 7
./minibyte 0b110 + 0b111
./minibyte 6 '*' 7
./minibyte 20 - 7
```

The first two commands both print:

```text
λs0.λs1.λs2.λs3.λs4.λs5.λs6.λs7.λx. s4 (s5 (s7 x)) 0b1101 13
```

The three output fields are the normalized lambda term, binary, and unsigned
decimal. Binary output omits leading zeroes; the lambda numeral always has eight
bit handlers. Quote `'*'` so your shell does not expand it into filenames.

Inputs are decimal `0..255` or binary with a `0b` prefix, such as `0b110`.
`0x` conventionally denotes hexadecimal and is rejected with a helpful message.
There is no expression parser: supply `NUMBER OP NUMBER` as three shell arguments.

Results wrap modulo 256. An unsigned overflow or underflow notice goes to stderr,
leaving the result on stdout:

```sh
./minibyte 255 + 1
# stdout: λs0.λs1.λs2.λs3.λs4.λs5.λs6.λs7.λx. x 0b0 0
# stderr: overflow: result wraps modulo 256
```

Successful calculations, including wrapped results, exit with code 0.
Invalid input exits with code 1. `./minibyte --help` prints usage.

## How the representation works

The handlers `s0` through `s7` represent weights `128,64,32,16,8,4,2,1`.
A set bit includes its handler once; an unset bit skips it. Examples:

```text
0  = λs0.λs1.λs2.λs3.λs4.λs5.λs6.λs7.λx. x
1  = λs0.λs1.λs2.λs3.λs4.λs5.λs6.λs7.λx. s7 x
2  = λs0.λs1.λs2.λs3.λs4.λs5.λs6.λs7.λx. s6 x
3  = λs0.λs1.λs2.λs3.λs4.λs5.λs6.λs7.λx. s6 (s7 x)
13 = λs0.λs1.λs2.λs3.λs4.λs5.λs6.λs7.λx. s4 (s5 (s7 x))
```

This is a binary encoding using separate handlers for bit positions.
Ordinary Church numerals instead repeatedly apply a single function.

`TRUE = λt.λf.t`, `FALSE = λt.λf.f`, and `I = λx.x` build Boolean gates.
To read a bit, apply the numeral to `MARK = λx.TRUE` at that bit's handler,
`I` at the other handlers, and `FALSE` as the seed. `PACK8` performs the reverse:
each Boolean selects its handler or `I`.

| Definition | Role |
| --- | --- |
| `FA` | One-bit full adder, passing its sum and carry to a continuation |
| `ADC8` / `ADC16` | Chain 8 or 16 full adders from least significant bit upward |
| `ADD` | `ADC8` with carry-in `FALSE`; returns the byte and final carry |
| `INV8` | Complement every input bit |
| `SUB` | Add the complement of the second operand with carry-in `TRUE`; invert final carry for the borrow flag |
| `SHL16` | Shift a sixteen-handler numeral left by remapping its handlers |
| `ADD16` | Sixteen-bit addition, used internally for multiplication |
| `MUL16` | Apply a sixteen-handler numeral to additions of shifted copies of the other operand |
| `WIDEN` / `LOW` | Zero-extend an input byte / select the low product byte |
| `HIGH_NONZERO` | Detect a nonzero high product byte for unsigned multiplication overflow |
| `PAIR`, `FIRST`, `SECOND` | Lambda-encoded result pair and its projections |

All byte products fit in sixteen bits (`255 * 255 = 65025`), so the high-byte
test detects multiplication overflow exactly. `core.lambda` contains every
generated helper definition; regenerate it with `./minibyte --defs`.

## Negative numbers and limits

The same eight bits also support two's complement. Interpret unsigned byte `u`
as `u` when `u < 128`, and as `u - 256` otherwise. Thus `11111101` means
either unsigned 253 or signed -3. The stored pattern and lambda term are identical.
Addition, subtraction, and multiplication produce the same low eight bits under
either interpretation.

This CLI accepts unsigned inputs and prints unsigned decimal. For example,
`./minibyte 2 - 5` produces 253; its signed interpretation is -3.
Signed interpretation has range `-128..127`. The notices detect **unsigned**
overflow/underflow; they do not detect signed overflow. For example, `127 + 1`
has no unsigned overflow but exceeds the signed range.

The byte domain has only 256 values. This front end is a calculator, not a
general-purpose or Turing-complete language. Division, negative input literals,
variables, and compound expressions are outside its syntax. The evaluator limits
each normalization to 2,000,000 transitions.

Scala is responsible for input parsing, constructing finite lambda syntax trees,
the generic evaluator, and output decoding. Its native arithmetic is used at those
boundaries and in the independent test oracle. The arithmetic operations and fault
flags themselves are evaluated from `Var`, `Lam`, and `App` terms. The printed
lambda term is read back from the computed normal form and renamed; it is not
generated from a native sum or product.

## Build and test

The source was compiled and tested with Scala 2.11.12 on Java 17.

```sh
./build.sh
scala -cp build/classes MiniByte 6 + 7
scala -cp build/classes MiniByteTests
```

Or test the prebuilt JAR:

```sh
./test.sh
./test.sh --exhaustive
```

The exhaustive test checks all 65,536 operand pairs for each operator against an
independent native oracle, including the wrapped result and unsigned fault.
See `validation.txt` for the actual results. The bundled Scala runtime's notices
are in `SCALA-LICENSE.txt`.
