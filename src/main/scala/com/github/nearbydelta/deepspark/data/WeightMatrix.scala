package com.github.nearbydelta.deepspark.data

import breeze.linalg._
import breeze.linalg.operators._
import breeze.numerics._
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import org.apache.spark.SparkContext

/**
 * __Trait__ that describes the algorithm for weight update
 *
 * Because each weight update requires history, we recommend to make inherited one as a class. 
 */
trait WeightBuilder extends Serializable with KryoSerializable {
  final def buildMatrix(weight: Weight[Matrix], row: Int, col: Int,
                        range: (Double, Double) = (1e-2, 2e-2)): Weight[Matrix] = {
    if (weight.isDefined) {
      buildTo(weight, weight.value)
    } else {
      val (from, to) = range
      val matx = DenseMatrix.rand[Double](row, col)
      matx :*= (to - from)
      matx :+= from
      buildTo(weight, matx)
    }
  }

  final def buildVector(weight: Weight[DataVec], row: Int,
                        range: (Double, Double) = (1e-2, 2e-2)): Weight[DataVec] = {
    if (weight.isDefined) {
      buildTo(weight, weight.value)
    } else {
      val (from, to) = range
      val matx = DenseVector.rand[Double](row)
      matx :*= (to - from)
      matx :+= from
      buildTo(weight, matx)
    }
  }

  def getUpdater(value: Matrix, delta: Matrix): Algorithm[Matrix]

  def getUpdater(value: DataVec, delta: DataVec): Algorithm[DataVec]

  def buildTo(weight: Weight[Matrix], value: Matrix): Weight[Matrix] = {
    weight withValue value
    weight withUpdater getUpdater(value, weight.getDelta)
    weight
  }

  def buildTo(weight: Weight[DataVec], value: DataVec): Weight[DataVec] = {
    weight withValue value
    weight withUpdater getUpdater(value, weight.getDelta)
    weight
  }
}

object Weight {
  private var clipping: Option[Double] = None

  def scalingDownBy(thr: Double) = {
    clipping = Some(thr)
  }

  def scaleCheck[X](x: X)
                   (implicit normImpl: norm.Impl[X, Double], mul: OpMulScalar.InPlaceImpl2[X, Double]): X =
    clipping match {
      case Some(thr) ⇒
        val n = normImpl(x)
        if (n >= thr) {
          mul(x, thr / n)
        }
        x
      case _ ⇒
        x
    }
}

class Weight[X] extends Serializable with KryoSerializable {
  private var _x: X = null.asInstanceOf[X]
  @transient private var _delta: X = _
  @transient private var _updater: Algorithm[X] = _

  def isDefined = _x != null

  def value = _x

  def updateBy(x: X)(implicit param$1: norm.Impl[X, Double], param$2: OpMulScalar.InPlaceImpl2[X, Double],
                     addInplace: OpAdd.InPlaceImpl2[X, X]) = {
    // Use Scaling Down Trick (Pascanu et al., 2013)
    addInplace(_delta, Weight.scaleCheck(x))
  }

  def getDelta = _delta

  def withValue(x: X)(implicit zero: Zero[X]) = {
    _x = x
    _delta = zero(x)
    this
  }

  def withUpdater(updater: Algorithm[X]) = {
    _updater = updater
    this
  }

  def update(count: Int): Unit =
    if (_updater != null) {
      _updater.update(count)
    } else {
      throw new IllegalStateException("Weight instance does not have any Algorithm instance")
    }

  def loss(implicit normImpl: norm.Impl[X, Double]): Double =
    if (_updater != null) {
      val n = normImpl(_x)
      n * n * _updater.l2factor
    } else {
      throw new IllegalStateException("Weight instance does not have any Algorithm instance")
    }

  override def write(kryo: Kryo, output: Output): Unit = {
    kryo.writeClassAndObject(output, _x)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    _x = kryo.readClassAndObject(input).asInstanceOf[X]
  }
}

trait Algorithm[X] {
  protected val x: X
  protected val dW: X

  def l2factor: Double

  /**
   * Execute the algorithm for given __Δweight__ and __weights__
   */
  def update(count: Int): Unit
}

/**
 * __Algorithm__: AdaDelta algorithm
 *
 * If you are trying to use this algorithm for your research, you should add a reference to [[http://www.matthewzeiler.com/pubs/googleTR2012/googleTR2012.pdf AdaDelta techinical report]].
 *
 * @param l2decay L,,2,, regularization factor `(Default 0.0001)`
 * @param historyDecay AdaDelta history decay factor `(Default 95% = 0.95)`
 * @param historyEpsilon AdaDelta base factor `(Default 1e-6)`
 *
 * @example {{{val algorithm = new AdaDelta(l2decay = 0.0001)}}}
 */
class AdaDelta(var l2decay: Double = 0.0001,
               var historyDecay: Double = 0.95,
               var historyEpsilon: Double = 1e-6)
  extends WeightBuilder {
  def this() = this(0.0001, 0.95, 1e-6)

  override def write(kryo: Kryo, output: Output): Unit = {
    output.writeDouble(l2decay)
    output.writeDouble(historyDecay)
    output.writeDouble(historyEpsilon)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    l2decay = input.readDouble()
    historyDecay = input.readDouble()
    historyEpsilon = input.readDouble()
  }

  class Updater[X](override protected val x: X,
                   override protected val dW: X)
                  (implicit private val zero: Zero[X],
                   implicit private val elemMul: OpMulScalar.Impl2[X, X, X],
                   implicit private val mul: OpMulScalar.Impl2[X, Double, X],
                   implicit private val mulScalarInplace: OpMulScalar.InPlaceImpl2[X, Double],
                   implicit private val addInplace: OpAdd.InPlaceImpl2[X, X],
                   implicit private val addScalar: OpAdd.Impl2[X, Double, X],
                   implicit private val root: sqrt.Impl[X, X],
                   implicit private val subInplace: OpSub.InPlaceImpl2[X, X],
                   implicit private val elemDiv: OpDiv.Impl2[X, X, X],
                   implicit private val set: OpSet.InPlaceImpl2[X, Double]) extends Algorithm[X] {
    private lazy val gradSq = zero(x)
    private lazy val deltaSq = zero(x)

    override def l2factor = l2decay

    /**
     * Execute the algorithm for given __Δ(weight)__ and __weights__
     */
    override def update(count: Int): Unit = {
      val d = mul(x, l2decay * 2)
      mulScalarInplace(dW, 1.0 / count)
      addInplace(d, dW)

      mulScalarInplace(gradSq, historyDecay)
      val decayD = elemMul(d, d)
      addInplace(gradSq, mul(decayD, 1.0 - historyDecay))

      val r1 = root(addScalar(deltaSq, historyEpsilon))
      val r2 = root(addScalar(gradSq, historyEpsilon))
      val rate = elemDiv(r1, r2)

      val dw = elemMul(d, rate)
      subInplace(x, dw)

      mulScalarInplace(deltaSq, historyDecay)
      val decayDW = elemMul(dw, dw)
      addInplace(deltaSq, mul(decayDW, 1.0 - historyDecay))

      set(dW, 0.0)
    }
  }

  override def getUpdater(value: Matrix, delta: Matrix): Algorithm[Matrix] =
    new Updater[Matrix](value, delta)

  override def getUpdater(value: DataVec, delta: DataVec): Algorithm[DataVec] =
    new Updater[DataVec](value, delta)

}

/**
 * __Algorithm__: AdaGrad algorithm.
 *
 * If you are trying to use this algorithm for your research, you should add a reference to [[http://www.magicbroom.info/Papers/DuchiHaSi10.pdf AdaGrad paper]].
 *
 * @param rate the learning rate `(Default 0.6)`
 * @param l2decay L,,2,, regularization factor `(Default 0.0001)`
 *
 *
 * @example {{{val algorithm = new AdaGrad(l2decay = 0.0001)}}}
 */
class AdaGrad(var rate: Double = 0.6,
              var l2decay: Double = 0.0001,
              var fudgeFactor: Double = 1e-6)
  extends WeightBuilder {
  def this() = this(0.6, 0.0001, 1e-6)

  override def write(kryo: Kryo, output: Output): Unit = {
    output.writeDouble(l2decay)
    output.writeDouble(rate)
    output.writeDouble(fudgeFactor)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    l2decay = input.readDouble()
    rate = input.readDouble()
    fudgeFactor = input.readDouble()
  }

  class Updater[X](override protected val x: X,
                   override protected val dW: X)
                  (implicit private val zero: Zero[X],
                   implicit private val elemMul: OpMulScalar.Impl2[X, X, X],
                   implicit private val mul: OpMulScalar.Impl2[X, Double, X],
                   implicit private val add: OpAdd.Impl2[X, Double, X],
                   implicit private val addInplace: OpAdd.InPlaceImpl2[X, X],
                   implicit private val root: sqrt.Impl[X, X],
                   implicit private val subInplace: OpSub.InPlaceImpl2[X, X],
                   implicit private val div: OpDiv.Impl2[Double, X, X],
                   implicit private val set: OpSet.InPlaceImpl2[X, Double]) extends Algorithm[X] {
    /** accumulated history of parameter updates */
    private lazy val history = zero(x)

    override def l2factor = l2decay

    /**
     * Execute the algorithm for given __Δweight__ and __weights__
     */
    override def update(count: Int): Unit = {
      val d = mul(x, l2decay * 2)
      addInplace(d, mul(dW, 1.0 / count))

      addInplace(history, elemMul(d, d))

      val dw = elemMul(d, div(rate, add(root(history), fudgeFactor)))
      subInplace(x, dw)

      set(dW, 0.0)
    }
  }


  override def getUpdater(value: Matrix, delta: Matrix): Algorithm[Matrix] =
    new Updater[Matrix](value, delta)

  override def getUpdater(value: DataVec, delta: DataVec): Algorithm[DataVec] =
    new Updater[DataVec](value, delta)
}

/**
 * __Algorithm__: Stochastic Gradient Descent
 *
 * Basic Gradient Descent rule with mini-batch training.
 *
 * @param rate the learning rate `(Default 0.03)`
 * @param l2decay L,,2,, regularization factor `(Default 0.0001)`
 * @param momentum Momentum factor for adaptive learning `(Default 0.0001)`
 *
 * @example {{{val algorithm = new StochasticGradientDescent(l2decay = 0.0001)}}}
 */
class StochasticGradientDescent(var rate: Double = 0.03,
                                var l2decay: Double = 0.0001,
                                var momentum: Double = 0.0001)
  extends WeightBuilder {
  def this() = this(0.03, 0.0001, 0.0001)

  override def write(kryo: Kryo, output: Output): Unit = {
    output.writeDouble(l2decay)
    output.writeDouble(rate)
    output.writeDouble(momentum)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    l2decay = input.readDouble()
    rate = input.readDouble()
    momentum = input.readDouble()
  }

  class Updater[X](override protected val x: X,
                   override protected val dW: X)
                  (implicit private val zero: Zero[X],
                   implicit private val mul: OpMulScalar.Impl2[X, Double, X],
                   implicit private val mulInplace: OpMulScalar.InPlaceImpl2[X, Double],
                   implicit private val addInplace: OpAdd.InPlaceImpl2[X, X],
                   implicit private val subInplace: OpSub.InPlaceImpl2[X, X],
                   implicit private val set: OpSet.InPlaceImpl2[X, Double]) extends Algorithm[X] {
    /** the last update of parameters */
    private lazy val lastDelta: X = if (momentum != 0) zero(x) else null.asInstanceOf[X]

    override def l2factor = l2decay

    /**
     * Execute the algorithm for given __Δweight__ and __weights__
     */
    def update(count: Int): Unit = {
      mulInplace(dW, 1.0 / count)

      val d = mul(x, l2decay * 2)
      addInplace(d, dW)
      if (lastDelta != null) {
        mulInplace(lastDelta, momentum)
        addInplace(lastDelta, d)
        subInplace(x, lastDelta)
      } else
        subInplace(x, d)

      set(dW, 0.0)
    }
  }


  override def getUpdater(value: Matrix, delta: Matrix): Algorithm[Matrix] =
    new Updater[Matrix](value, delta)

  override def getUpdater(value: DataVec, delta: DataVec): Algorithm[DataVec] =
    new Updater[DataVec](value, delta)
}