package bus.simplebus

import chisel3._
import chisel3.util._

import utils._

class SimpleBusCrossbar1toN(addressSpace: List[(Long, Long)]) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SimpleBusUC)
    val out = Vec(addressSpace.length, new SimpleBusUC)
  })

  val s_idle :: s_resp :: Nil = Enum(2)
  val state = RegInit(s_idle)

  // select the output channel according to the address
  val addr = io.in.req.bits.addr
  val outSelVec = VecInit(addressSpace.map(
    range => (addr >= range._1.U && addr < (range._1 + range._2).U)))
  val outSelIdx = PriorityEncoder(outSelVec)
  val outSel = io.out(outSelIdx)
  val outSelIdxResp = RegEnable(outSelIdx, outSel.req.fire() && (state === s_idle))
  val outSelResp = io.out(outSelIdxResp)

  when(!(!io.in.req.valid || outSelVec.asUInt.orR) || !(!(io.in.req.valid && outSelVec.asUInt.andR))){printf("[ERROR] bad addr %x, time %d\n", addr, GTimer())}
  assert(!io.in.req.valid || outSelVec.asUInt.orR, "address decode error, bad addr = 0x%x\n", addr)
  assert(!(io.in.req.valid && outSelVec.asUInt.andR), "address decode error, bad addr = 0x%x\n", addr)

  // bind out.req channel
  (io.out zip outSelVec).map { case (o, v) => {
    o.req.bits := io.in.req.bits
    o.req.valid := v && (io.in.req.valid && (state === s_idle))
    o.resp.ready := v
  }}

  switch (state) {
    is (s_idle) { when (outSel.req.fire()) { state := s_resp } }
    is (s_resp) { when (outSelResp.resp.fire()) { state := s_idle } }
  }

  io.in.resp.valid := outSelResp.resp.fire()
  io.in.resp.bits <> outSelResp.resp.bits
  outSelResp.resp.ready := io.in.resp.ready
  io.in.req.ready := outSel.req.ready

  Debug() {
    when (state === s_idle && io.in.req.valid) {
      printf(p"${GTimer()}: xbar: in.req: ${io.in.req.bits}\n")
    }

    when (outSel.req.fire()) {
      printf(p"${GTimer()}: xbar: outSelIdx = ${outSelIdx}, outSel.req: ${outSel.req.bits}\n")
    }
    when (outSel.resp.fire()) {
      printf(p"${GTimer()}: xbar: outSelIdx= ${outSelIdx}, outSel.resp: ${outSel.resp.bits}\n")
    }

    when (io.in.resp.fire()) {
      printf(p"${GTimer()}: xbar: in.resp: ${io.in.resp.bits}\n")
    }
  }
}

class SimpleBusCrossbarNto1(n: Int, userBits:Int = 0) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(n, new SimpleBusUC(userBits)))
    val out = new SimpleBusUC(userBits)
  })

  val s_idle :: s_readResp :: s_writeResp :: Nil = Enum(3)
  val state = RegInit(s_idle)

  val lockWriteFun = ((x: SimpleBusReqBundle) => x.isWrite() && x.isBurst())
  val inputArb = Module(new LockingArbiter(chiselTypeOf(io.in(0).req.bits), n, 8, Some(lockWriteFun)))
  (inputArb.io.in zip io.in.map(_.req)).map{ case (arb, in) => arb <> in }
  val thisReq = inputArb.io.out
  assert(!(thisReq.valid && !thisReq.bits.isRead() && !thisReq.bits.isWrite()))
  val inflightSrc = Reg(UInt(log2Up(n).W))

  io.out.req.bits := thisReq.bits
  // bind correct valid and ready signals
  io.out.req.valid := thisReq.valid && (state === s_idle)
  thisReq.ready := io.out.req.ready && (state === s_idle)

  io.in.map(_.resp.bits := io.out.resp.bits)
  io.in.map(_.resp.valid := false.B)
  (io.in(inflightSrc).resp, io.out.resp) match { case (l, r) => {
    l.valid := r.valid
    r.ready := l.ready
  }}

  switch (state) {
    is (s_idle) {
      when (thisReq.fire()) {
        inflightSrc := inputArb.io.chosen
        when (thisReq.bits.isRead()) { state := s_readResp }
        .elsewhen (thisReq.bits.isWriteLast() || thisReq.bits.isWriteSingle()) { state := s_writeResp }
      }
    }
    is (s_readResp) { when (io.out.resp.fire() && io.out.resp.bits.isReadLast()) { state := s_idle } }
    is (s_writeResp) { when (io.out.resp.fire()) { state := s_idle } }
  }
}

class SimpleBusCrossbar(n: Int, addressSpace: List[(Long, Long)]) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(n, new SimpleBusUC))
    val out = Vec(addressSpace.length, new SimpleBusUC)
  })

  val inXbar = Module(new SimpleBusCrossbarNto1(n))
  val outXbar = Module(new SimpleBusCrossbar1toN(addressSpace))
  inXbar.io.in <> io.in
  outXbar.io.in <> inXbar.io.out
  io.out <> outXbar.io.out
}

class SimpleBusAutoIDCrossbarNto1(n: Int, userBits: Int = 0) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(n, new SimpleBusUC(userBits)))
    val out = new SimpleBusUC(userBits, idBits = n)
  })

  val reqValid = WireInit(VecInit(List.tabulate(n)(i => io.in(i).req.valid)))
  val reqSelect = PriorityEncoder(reqValid.asUInt)
  val reqSelectVec = PriorityEncoderOH(reqValid.asUInt)

  // ID will be automatically assigned for master devices
  // TODO: use Mux1H?
  io.out.req.bits.addr := io.in(reqSelect).req.bits.addr
  io.out.req.bits.size := io.in(reqSelect).req.bits.size
  io.out.req.bits.cmd := io.in(reqSelect).req.bits.cmd
  io.out.req.bits.wmask := io.in(reqSelect).req.bits.wmask
  io.out.req.bits.wdata := io.in(reqSelect).req.bits.wdata
  if(userBits > 0){
    io.out.req.bits.user.get := io.in(reqSelect).req.bits.user.get
  }

  io.out.req.valid := reqValid.asUInt.orR
  io.out.req.bits.id.get := reqSelectVec.asUInt // Simple bus ID is OH 
  io.out.resp.ready := io.in(reqSelect).resp.ready
  // assert(io.out.resp.ready)

  for(i <- 0 until n){
    io.in(i).req.ready := io.out.req.ready && reqSelectVec(i)
    io.in(i).resp.valid := io.out.resp.valid && io.out.resp.bits.id.get(i)
    io.in(i).resp.bits.cmd := io.out.resp.bits.cmd
    io.in(i).resp.bits.rdata := io.out.resp.bits.rdata
    if(userBits > 0){
      io.in(i).resp.bits.user.get := io.out.resp.bits.user.get
    }
  }

  Debug(){
    when(io.out.req.fire()){
      printf("[Crossbar REQ] addr %x cmd %x select %b\n", io.out.req.bits.addr, io.out.req.bits.cmd, reqSelectVec)
    }
    when(io.out.resp.fire()){
      printf("[Crossbar RESP] data %x select %b\n", io.out.resp.bits.rdata, io.out.resp.bits.id.get)
    }
  }

}