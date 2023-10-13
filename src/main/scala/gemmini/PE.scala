// See README.md for license details.
package gemmini

import chisel3._
import chisel3.util._

class PEControl[T <: Data : Arithmetic](accType: T) extends Bundle {
  val dataflow = UInt(1.W) // TODO make this an Enum
  val propagate = UInt(1.W) // Which register should be propagated (and which should be accumulated)?
  val shift = UInt(log2Up(accType.getWidth).W) // TODO this isn't correct for Floats

}

class MacUnit[T <: Data](inputType: T, cType: T, dType: T) (implicit ev: Arithmetic[T]) extends Module {
  import ev._
  val io = IO(new Bundle {
    val in_a  = Input(inputType)
    val in_b  = Input(inputType)
    val in_c  = Input(cType)
    val out_d = Output(dType)
  })

  io.out_d := io.in_c.mac(io.in_a, io.in_b)
}

// TODO update documentation
/**
  * A PE implementing a MAC operation. Configured as fully combinational when integrated into a Mesh.
  * @param width Data width of operands
  */
class PE[T <: Data](inputType: T, outputType: T, accType: T, df: Dataflow.Value, max_simultaneous_matmuls: Int)
                   (implicit ev: Arithmetic[T]) extends Module { // Debugging variables
  import ev._

  val io = IO(new Bundle {
    val in_a = Input(inputType)
    val in_b = Input(outputType)
    val in_d = Input(outputType)
    val out_a = Output(inputType)
    val out_b = Output(outputType)
    val out_c = Output(outputType)

    val in_control = Input(new PEControl(accType))
    val out_control = Output(new PEControl(accType))

    val in_id = Input(UInt(log2Up(max_simultaneous_matmuls).W))
    val out_id = Output(UInt(log2Up(max_simultaneous_matmuls).W))

    val in_last = Input(Bool())
    val out_last = Output(Bool())

    val in_valid = Input(Bool())
    val out_valid = Output(Bool())

    val bad_dataflow = Output(Bool())

    // added for zero-gating

    val in_a_zero = Input(Bool())
    val in_b_zero = Input(Bool())
    val in_d_zero = Input(Bool())
    val out_a_zero = Output(Bool())
    val out_b_zero = Output(Bool())
    val out_c_zero = Output(Bool())
  })

  val cType = if (df == Dataflow.WS) inputType else accType

  // When creating PEs that support multiple dataflows, the
  // elaboration/synthesis tools often fail to consolidate and de-duplicate
  // MAC units. To force mac circuitry to be re-used, we create a "mac_unit"
  // module here which just performs a single MAC operation
  val mac_unit = Module(new MacUnit(inputType,
    if (df == Dataflow.WS) outputType else accType, outputType))

  val a  = io.in_a
  val b  = io.in_b
  val d  = io.in_d
  val c1 = Reg(cType)
  val c2 = Reg(cType)
  val dataflow = io.in_control.dataflow
  val prop  = io.in_control.propagate
  val shift = io.in_control.shift
  val id = io.in_id
  val last = io.in_last
  val valid = io.in_valid

  // added for zero-gating
  val a_zero = io.in_a_zero
  val b_zero = io.in_b_zero
  val d_zero = io.in_d_zero
  val c1_zero = Reg(Bool())
  val c2_zero = Reg(Bool())


  io.out_a := a
  io.out_control.dataflow := dataflow
  io.out_control.propagate := prop
  io.out_control.shift := shift
  io.out_id := id
  io.out_last := last
  io.out_valid := valid

  // for zero-gating
  io.out_a_zero := a_zero
  io.out_c_zero := io.out_c.asUInt === 0.U

  mac_unit.io.in_a := a

  val last_s = RegEnable(prop, valid)
  val flip = last_s =/= prop
  val shift_offset = Mux(flip, shift, 0.U)

  // Which dataflow are we using?
  val OUTPUT_STATIONARY = Dataflow.OS.id.U(1.W)
  val WEIGHT_STATIONARY = Dataflow.WS.id.U(1.W)

  // Is c1 being computed on, or propagated forward (in the output-stationary dataflow)?
  val COMPUTE = 0.U(1.W)
  val PROPAGATE = 1.U(1.W)

  io.bad_dataflow := false.B
  when ((df == Dataflow.OS).B || ((df == Dataflow.BOTH).B && dataflow === OUTPUT_STATIONARY)) {
    when(prop === PROPAGATE) {
      io.out_c := (c1 >> shift_offset).clippedToWidthOf(outputType)
      io.out_b := b
      mac_unit.io.in_b := b.asTypeOf(inputType)
      mac_unit.io.in_c := c2
      c2 := mac_unit.io.out_d
      c1 := d.withWidthOf(cType)
    }.otherwise {
      io.out_c := (c2 >> shift_offset).clippedToWidthOf(outputType)
      io.out_b := b
      mac_unit.io.in_b := b.asTypeOf(inputType)
      mac_unit.io.in_c := c1
      c1 := mac_unit.io.out_d
      c2 := d.withWidthOf(cType)
    }
    io.out_b_zero := b_zero
  }.elsewhen ((df == Dataflow.WS).B || ((df == Dataflow.BOTH).B && dataflow === WEIGHT_STATIONARY)) {
    when(prop === PROPAGATE) {
      io.out_c := c1
      mac_unit.io.in_b := c2.asTypeOf(inputType)
      //mac_unit.io.in_c := b
      //io.out_b := mac_unit.io.out_d
      //c1 := d
      //added for zero-gating
      //io.out_b := Mux(c2_zero, b, Mux(b_zero, 0.U.asTypeOf(b), b).mac(Mux(a_zero, 0.U.asTypeOf(a), a), c2.asTypeOf(inputType)))
      mac_unit.io.in_a := Mux(a_zero, 0.U.asTypeOf(a), a)
      mac_unit.io.in_c := Mux(b_zero, 0.U.asTypeOf(b), b)
      io.out_b := Mux(c2_zero, b, mac_unit.io.out_d)
      c1 := Mux(d_zero, 0.U.asTypeOf(d), d)
      c1_zero := d_zero
      io.out_b_zero := Mux(c2_zero, b_zero, io.out_b.asUInt === 0.U)
    }.otherwise {
      io.out_c := c2
      mac_unit.io.in_b := c1.asTypeOf(inputType)
      //mac_unit.io.in_c := b
      //io.out_b := mac_unit.io.out_d
      //c2 := d
      //added for zero-gating
      //io.out_b := Mux(c1_zero, b, Mux(b_zero, 0.U.asTypeOf(b), b).mac(Mux(a_zero, 0.U.asTypeOf(a), a), c1.asTypeOf(inputType)))
      mac_unit.io.in_a := Mux(a_zero, 0.U.asTypeOf(a), a)
      mac_unit.io.in_c := Mux(b_zero, 0.U.asTypeOf(b), b)
      io.out_b := Mux(c1_zero, b, mac_unit.io.out_d)
      c2 := Mux(d_zero, 0.U.asTypeOf(d), d)
      c2_zero := d_zero
      io.out_b_zero := Mux(c1_zero, b_zero, io.out_b.asUInt === 0.U)
    }
  }.otherwise {
    io.bad_dataflow := true.B
    //assert(false.B, "unknown dataflow")
    io.out_c := DontCare
    io.out_b := DontCare
    // add for zero-gating
    io.out_b_zero := DontCare
    io.out_c_zero := DontCare
    mac_unit.io.in_b := b.asTypeOf(inputType)
    mac_unit.io.in_c := c2
  }

  when (!valid) {
    c1 := c1
    c2 := c2
    mac_unit.io.in_b := DontCare
    mac_unit.io.in_c := DontCare
    //addd for zero-gating
    c1_zero := c1_zero
    c2_zero := c2_zero
  }
  /*
  when (io.in_a_zero || io.in_b_zero) {
    c1 := c1
    c2 := c2
    mac_unit.io.in_b := DontCare
    mac_unit.io.in_c := DontCare
    //addd for zero-gating
    //c1_zero := c1_zero
    //c2_zero := c2_zero
  }

   */
}
