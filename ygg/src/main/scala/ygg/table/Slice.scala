package ygg.table

import ygg.cf
import TransSpecModule._
import ygg.common._
import scalaz.{ =?> => _, _ }, Ordering._
import ygg.macros.Spire._
import ygg.json._
import ygg.data._

sealed trait Slice {
  def size: Int
  def columns: ColumnMap
}
final class DirectSlice(
    val size: Int,
    val columns: ColumnMap
) extends Slice

final class DerefSlice(
    source: Slice,
    derefBy: Int =?> CPathNode
) extends Slice {
  val size    = source.size
  val columns = source dereferencedColumns derefBy
}

class SliceOps(private val source: Slice) extends AnyVal {
  import Slice._
  import TableModule._

  def size: Int          = source.size
  def isEmpty: Boolean   = size == 0
  def nonEmpty           = !isEmpty
  def columns: ColumnMap = source.columns

  def logicalColumns: JType => Set[Column] = { jtpe =>
    // TODO Use a flatMap and:
    // If ColumnRef(_, CArrayType(_)) and jType has a JArrayFixedT of this type,
    //   then we need to map these to multiple columns.
    // Else if Schema.includes(...), then return List(col).
    // Otherwise return Nil.
    columns collect {
      case (ColumnRef(cpath, ctype), col) if Schema.includes(jtpe, cpath, ctype) => col
    } toSet
  }

  def isDefinedAt(row: Int) = columns.values.exists(_.isDefinedAt(row))

  def definedAt: BitSet = doto(Bits())(defined => columns foreach { case (_, col) => defined or col.definedAt(0, size) })

  def mapRoot(f: CF1): Slice =
    Slice(source.size, {
      val resultColumns = for {
        col    <- source.columns collect { case (ref, col) if ref.selector == CPath.Identity => col }
        result <- f(col)
      } yield result

      resultColumns.groupBy(_.tpe) map {
        case (tpe, cols) => (ColumnRef.id(tpe), cols.reduceLeft((c1, c2) => Column.unionRightSemigroup.append(c1, c2)))
      }
    })

  def mapColumns(f: CF1): Slice =
    Slice(source.size, {
      val resultColumns: Seq[ColumnRef -> Column] = for {
        (ref, col) <- source.columns.toSeq
        result     <- f(col)
      } yield (ref.copy(ctype = result.tpe), result)

      resultColumns.groupBy(_._1) map {
        case (ref, pairs) => (ref, pairs.map(_._2).reduceLeft((c1, c2) => Column.unionRightSemigroup.append(c1, c2)))
      } toMap
    })

  def toArray[A](implicit tpe0: CValueType[A]): Slice = {
    val cols0 = (source.columns).toList sortBy { case (ref, _) => ref.selector }
    val cols  = cols0 map { case (_, col)                      => col }

    def inflate[@spec A: CTag](cols: Array[Int => A], row: Int) = {
      val as = new Array[A](cols.length)
      var i  = 0
      while (i < cols.length) {
        as(i) = cols(i)(row)
        i += 1
      }
      as
    }

    def loopForall[A <: Column](cols: Array[A])(row: Int) = !cols.isEmpty && Loop.forall(cols)(_ isDefinedAt row)

    def rhs = tpe0 match {
      case CLong =>
        val longcols = cols.collect { case (col: LongColumn) => col }.toArray

        new HomogeneousArrayColumn[Long] {
          private val cols: Array[Int => Long] = longcols map (col => col apply _)

          val tpe                          = CArrayType(CLong)
          def isDefinedAt(row: Int)        = loopForall[LongColumn](longcols)(row)
          def apply(row: Int): Array[Long] = inflate(cols, row)
        }
      case CDouble =>
        val doublecols = cols.collect { case (col: DoubleColumn) => col }.toArray
        new HomogeneousArrayColumn[Double] {
          private val cols: Array[Int => Double] = doublecols map (col => col apply _)

          val tpe                            = CArrayType(CDouble)
          def isDefinedAt(row: Int)          = loopForall[DoubleColumn](doublecols)(row)
          def apply(row: Int): Array[Double] = inflate(cols, row)
        }
      case CNum =>
        val numcols = cols.collect { case (col: NumColumn) => col }.toArray
        new HomogeneousArrayColumn[BigDecimal] {
          private val cols: Array[Int => BigDecimal] = numcols map (col => col apply _)

          val tpe                                = CArrayType(CNum)
          def isDefinedAt(row: Int)              = loopForall[NumColumn](numcols)(row)
          def apply(row: Int): Array[BigDecimal] = inflate(cols, row)
        }
      case CBoolean =>
        val boolcols = cols.collect { case (col: BoolColumn) => col }.toArray
        new HomogeneousArrayColumn[Boolean] {
          private val cols: Array[Int => Boolean] = boolcols map (col => col apply _)

          val tpe                             = CArrayType(CBoolean)
          def isDefinedAt(row: Int)           = loopForall[BoolColumn](boolcols)(row)
          def apply(row: Int): Array[Boolean] = inflate(cols, row)
        }
      case CString =>
        val strcols = cols.collect { case (col: StrColumn) => col }.toArray
        new HomogeneousArrayColumn[String] {
          private val cols: Array[Int => String] = strcols map (col => col apply _)

          val tpe                            = CArrayType(CString)
          def isDefinedAt(row: Int)          = loopForall[StrColumn](strcols)(row)
          def apply(row: Int): Array[String] = inflate(cols, row)
        }
      case _ => abort("unsupported type")
    }

    def key = ColumnRef(CPath(CPathArray), CArrayType(tpe0))
    Slice(source.size, Map(key -> rhs))
  }

  /**
    * Transform this slice such that its columns are only defined for row indices
    * in the given BitSet.
    */
  def redefineWith(s: BitSet): Slice = mapColumns(cf.filter(0, size, s))

  def definedConst(value: CValue): Slice =
    Slice(source.size, Map(value match {
      case CString(s) =>
        (ColumnRef.id(CString), new StrColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
          def apply(row: Int)       = s
        })
      case CBoolean(b) =>
        (ColumnRef.id(CBoolean), new BoolColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
          def apply(row: Int)       = b
        })
      case CLong(l) =>
        (ColumnRef.id(CLong), new LongColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
          def apply(row: Int)       = l
        })
      case CDouble(d) =>
        (ColumnRef.id(CDouble), new DoubleColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
          def apply(row: Int)       = d
        })
      case CNum(n) =>
        (ColumnRef.id(CNum), new NumColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
          def apply(row: Int)       = n
        })
      case CDate(d) =>
        (ColumnRef.id(CDate), new DateColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
          def apply(row: Int)       = d
        })
      case CPeriod(p) =>
        (ColumnRef.id(CPeriod), new PeriodColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
          def apply(row: Int)       = p
        })
      case value: CArray[a] =>
        (ColumnRef.id(value.cType), new HomogeneousArrayColumn[a] {
          val tpe                   = value.cType
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
          def apply(row: Int)       = value.value
        })
      case CNull =>
        (ColumnRef.id(CNull), new NullColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
        })
      case CEmptyObject =>
        (ColumnRef.id(CEmptyObject), new EmptyObjectColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
        })
      case CEmptyArray =>
        (ColumnRef.id(CEmptyArray), new EmptyArrayColumn {
          def isDefinedAt(row: Int) = source.isDefinedAt(row)
        })
      case CUndefined => abort("Cannot define a constant undefined value")
    }))

  def deref(node: CPathNode): Slice = Slice(
    source.size,
    node match {
      case CPathIndex(i) =>
        source.columns collect {
          case (ColumnRef(CPath(CPathArray, xs @ _ *), CArrayType(elemType)), col: HomogeneousArrayColumn[_]) =>
            (ColumnRef(CPath(xs: _*), elemType), col.select(i))

          case (ColumnRef(CPath(CPathIndex(`i`), xs @ _ *), ctype), col) =>
            (ColumnRef(CPath(xs: _*), ctype), col)
        }

      case _ =>
        source.columns collect {
          case (ColumnRef(CPath(`node`, xs @ _ *), ctype), col) =>
            (ColumnRef(CPath(xs: _*), ctype), col)
        }
    }
  )

  def wrap(wrapper: CPathNode): Slice = Slice(
    source.size,
    // This is a little weird; CPathArray actually wraps in CPathIndex(0).
    // Unfortunately, CArrayType(_) cannot wrap CNullTypes, so we can't just
    // arbitrarily wrap everything in a CPathArray.
    wrapper match {
      case CPathArray =>
        source.columns map {
          case (ColumnRef(CPath(nodes @ _ *), ctype), col) =>
            (ColumnRef(CPath(CPathIndex(0) +: nodes: _*), ctype), col)
        }
      case _ =>
        source.columns map {
          case (ColumnRef(CPath(nodes @ _ *), ctype), col) =>
            (ColumnRef(CPath(wrapper +: nodes: _*), ctype), col)
        }
    }
  )

  // ARRAYS:
  // TODO Here, if we delete a JPathIndex/JArrayFixedT, then we need to
  // construct a new Homo*ArrayColumn that has some indices missing.
  //
  // -- I've added a col.without(indicies) method to H*ArrayColumn to support
  // this operation.
  //
  def delete(jtype: JType): Slice = {
    def fixArrays(columns: ColumnMap): ColumnMap = {
      columns.toSeq
        .sortBy(_._1)
        .foldLeft((Map.empty[Vector[CPathNode], Int], Map.empty[ColumnRef, Column])) {
          case ((arrayPaths, acc), (ColumnRef(jpath, ctype), col)) =>
            val (arrayPaths0, nodes) = jpath.nodes.foldLeft((arrayPaths, Vector.empty[CPathNode])) {
              case ((ap, nodes), CPathIndex(_)) =>
                val idx = ap.getOrElse(nodes, -1) + 1
                (ap + (nodes -> idx), nodes :+ CPathIndex(idx))

              case ((ap, nodes), fieldNode) => (ap, nodes :+ fieldNode)
            }

            (arrayPaths0, acc + (ColumnRef(CPath(nodes: _*), ctype) -> col))
        }
        ._2
    }

    // Used for homogeneous arrays. Constructs a function, suitable for use in a
    // flatMap, that will modify the homogeneous array according to `jType`.
    //
    def flattenDeleteTree[A](jType: JType, cType: CValueType[A], cPath: CPath): A => Option[A] = {
      val delete: A => Option[A] = _ => None
      val retain: A => Option[A] = Some(_)

      (jType, cType, cPath) match {
        case (JUnionT(aJType, bJType), _, _) =>
          flattenDeleteTree(aJType, cType, cPath) andThen (_ flatMap flattenDeleteTree(bJType, cType, cPath))
        case (JTextT, CString, CPath.Identity) =>
          delete
        case (JBooleanT, CBoolean, CPath.Identity) =>
          delete
        case (JNumberT, CLong | CDouble | CNum, CPath.Identity) =>
          delete
        case (JObjectUnfixedT, _, CPath(CPathField(_), _ *)) =>
          delete
        case (JObjectFixedT(fields), _, CPath(CPathField(name), cPath @ _ *)) =>
          fields get name map (flattenDeleteTree(_, cType, CPath(cPath: _*))) getOrElse (retain)
        case (JArrayUnfixedT, _, CPath(CPathArray | CPathIndex(_), _ *)) =>
          delete
        case (JArrayFixedT(elems), cType, CPath(CPathIndex(i), cPath @ _ *)) =>
          elems get i map (flattenDeleteTree(_, cType, CPath(cPath: _*))) getOrElse (retain)
        case (JArrayFixedT(elems), CArrayType(cElemType), CPath(CPathArray, cPath @ _ *)) =>
          val mappers = elems mapValues (flattenDeleteTree(_, cElemType, CPath(cPath: _*)))
          xs =>
            Some(xs.zipWithIndex map {
              case (x, j) =>
                mappers get j match {
                  case Some(f) => f(x)
                  case None    => x
                }
            })
        case (JArrayHomogeneousT(jType), CArrayType(cType), CPath(CPathArray, _ *)) if Schema.ctypes(jType)(cType) =>
          delete
        case _ =>
          retain
      }
    }

    Slice(
      source.size,
      fixArrays(source.columns flatMap {
        case (ColumnRef(cpath, ctype), _) if Schema.includes(jtype, cpath, ctype) => None

        case (ref @ ColumnRef(cpath, ctype: CArrayType[a]), col: HomogeneousArrayColumn[_]) if ctype == col.tpe =>
          val trans = flattenDeleteTree(jtype, ctype, cpath)
          Some((ref, new HomogeneousArrayColumn[a] {
            val tpe                       = ctype
            def isDefinedAt(row: Int)     = col.isDefinedAt(row)
            def apply(row: Int): Array[a] = trans(col(row).asInstanceOf[Array[a]]) getOrElse abort("Oh dear, this cannot be happening to me.")
          }))

        case (ref, col) => Some(ref -> col)
      })
    )
  }

  def deleteFields(prefixes: scSet[CPathField]): Slice = {
    val (removed, withoutPrefixes) = source.columns partition {
      case (ColumnRef(CPath(head @ CPathField(_), _ @_ *), _), _) => prefixes contains head
      case _                                                      => false
    }

    val becomeEmpty = Bits.filteredRange(0, source.size) { i =>
      Column.isDefinedAt(removed.values.toArray, i) && !Column.isDefinedAt(withoutPrefixes.values.toArray, i)
    }

    val ref = ColumnRef.id(CEmptyObject)

    // The object might have become empty. Make the
    // EmptyObjectColumn defined at the row position.
    lazy val emptyObjectColumn = withoutPrefixes get ref map { c =>
      new EmptyObjectColumn {
        def isDefinedAt(row: Int) = c.isDefinedAt(row) || becomeEmpty(row)
      }
    } getOrElse {
      new EmptyObjectColumn {
        def isDefinedAt(row: Int) = becomeEmpty(row)
      }
    }

    if (becomeEmpty.isEmpty)
      Slice(size, withoutPrefixes)
    else
      Slice(size, withoutPrefixes + (ref -> emptyObjectColumn))
  }

  def typed(jtpe: JType): Slice =
    Slice(size, source.columns filter { case (ColumnRef(path, ctpe), _) => Schema.requiredBy(jtpe, path, ctpe) })

  def typedSubsumes(jtpe: JType): Slice = {
    val tuples: Seq[CPath -> CType] = source.columns.map({ case (ColumnRef(path, ctpe), _) => (path, ctpe) })(collection.breakOut)
    val columns = if (Schema.subsumes(tuples, jtpe)) {
      source.columns filter { case (ColumnRef(path, ctpe), _) => Schema.requiredBy(jtpe, path, ctpe) }
    } else {
      Map.empty[ColumnRef, Column]
    }

    Slice(source.size, columns)
  }

  /**
    * returns a BoolColumn that is true if row subsumes jtype, false otherwise (unless undefined)
    * determine if the supplied jtype subsumes all the columns
    * if false, return a BoolColumn with all falses, defined by union
    * if true, collect just those columns that the jtype specifies
    * then on a row-by-row basis, using a BitSet, we use `Schema.findTypes(...)` to determine the Boolean values
    */
  def isType(jtpe: JType): Slice = {
    val pathsAndTypes: Seq[CPath -> CType] = source.columns.toSeq map { case (ColumnRef(selector, ctype), _) => (selector, ctype) }
    // we cannot just use subsumes because there could be rows with undefineds in them
    val subsumes    = Schema.subsumes(pathsAndTypes, jtpe)
    val definedBits = (source.columns).values.map(_.definedAt(0, size)).reduceOption(_ | _) getOrElse new BitSet

    def mapValue = (
      if (subsumes) {
        val cols         = source.columns filter { case (ColumnRef(path, ctpe), _) => Schema.requiredBy(jtpe, path, ctpe) }
        val included     = Schema.findTypes(jtpe, CPath.Identity, cols, size)
        val includedBits = Bits.filteredRange(0, size)(included)

        BoolColumn.Either(definedBits, includedBits)
      } else BoolColumn.False(definedBits)
    )

    Slice(source.size, Map(ColumnRef.id(CBoolean) -> mapValue))
  }

  def arraySwap(index: Int): Slice =
    Slice(source.size, source.columns collect {
      case (ColumnRef(cPath @ CPath(CPathArray, _ *), cType), col: HomogeneousArrayColumn[a]) =>
        (ColumnRef(cPath, cType), new HomogeneousArrayColumn[a] {
          val tpe                   = col.tpe
          def isDefinedAt(row: Int) = col.isDefinedAt(row)
          def apply(row: Int) = {
            val xs = col(row)
            if (index >= xs.length) xs
            else {
              val ys = tpe.elemType.classTag.newArray(xs.length)

              var i = 1
              while (i < ys.length) {
                ys(i) = xs(i)
                i += 1
              }

              ys(0) = xs(index)
              ys(index) = xs(0)
              ys
            }
          }
        })

      case (ColumnRef(CPath(CPathIndex(0), xs @ _ *), ctype), col) =>
        (ColumnRef(CPath(CPathIndex(index) +: xs: _*), ctype), col)

      case (ColumnRef(CPath(CPathIndex(`index`), xs @ _ *), ctype), col) =>
        (ColumnRef(CPath(CPathIndex(0) +: xs: _*), ctype), col)

      case c @ (ColumnRef(CPath(CPathIndex(i), xs @ _ *), ctype), col) => c
    })

  // Takes an array where the indices correspond to indices in this slice,
  // and the values give the indices in the sparsened slice.
  def sparsen(index: Array[Int], toSize: Int): Slice = Slice(
    toSize,
    source.columns lazyMapValues (col => cf.Sparsen(index, toSize)(col).get) //sparsen is total
  )
  def remap(indices: ArrayIntList): Slice = Slice(
    indices.size,
    source.columns lazyMapValues (col => cf.RemapIndices(indices).apply(col).get)
  )

  def map(from: CPath, to: CPath)(f: CF1): Slice = {
    val resultColumns = for {
      col    <- source.columns collect { case (ref, col) if ref.selector.hasPrefix(from) => col }
      result <- f(col)
    } yield result

    val columns: ColumnMap = {
      resultColumns.groupBy(_.tpe) map {
        case (tpe, cols) => (ColumnRef(to, tpe), cols.reduceLeft((c1, c2) => Column.unionRightSemigroup.append(c1, c2)))
      }
    }

    Slice(source.size, columns)
  }

  def map2(froml: CPath, fromr: CPath, to: CPath)(f: CF2): Slice = Slice(
    source.size, {
      val resultColumns = for {
        left   <- source.columns collect { case (ref, col) if ref.selector.hasPrefix(froml) => col }
        right  <- source.columns collect { case (ref, col) if ref.selector.hasPrefix(fromr) => col }
        result <- f(left, right)
      } yield result

      resultColumns.groupBy(_.tpe) map { case (tpe, cols) => (ColumnRef(to, tpe), cols.reduceLeft((c1, c2) => Column.unionRightSemigroup.append(c1, c2))) }
    }
  )

  def filterDefined(filter: Slice, definedness: Definedness): Slice = {
    val colValues = filter.columns.values.toArray
    lazy val defined = definedness match {
      case AnyDefined =>
        Bits.filteredRange(0, source.size) { i =>
          colValues.exists(_.isDefinedAt(i))
        }

      case AllDefined =>
        if (colValues.isEmpty)
          new BitSet
        else
          Bits.filteredRange(0, source.size) { i =>
            colValues.forall(_.isDefinedAt(i))
          }
    }

    Slice(source.size, source.columns lazyMapValues (col => cf.filter(0, source.size, defined)(col).get))
  }

  def compact(filter: Slice, definedness: Definedness): Slice = {
    val cols = filter.columns
    val retained = definedness match {
      case AnyDefined =>
        doto(new ArrayIntList) { acc =>
          cforRange(0 until filter.size)(i => if (cols.values exists (_ isDefinedAt i)) acc.add(i))
        }

      case AllDefined =>
        doto(new ArrayIntList) { acc =>
          val (numCols, otherCols) = cols partition {
            case (ColumnRef(_, ctype), _) =>
              ctype.isNumeric
          }

          val grouped = numCols groupBy { case (ColumnRef(cpath, _), _) => cpath }

          cforRange(0 until filter.size) { i =>
            def numBools  = grouped.values map (_.values.toArray exists (_ isDefinedAt i))
            def numBool   = numBools reduce (_ && _)
            def otherBool = otherCols.values.toArray forall (_ isDefinedAt i)

            if (otherBool && numBool) acc.add(i)
          }
        }
    }

    Slice(retained.size, source.columns lazyMapValues (_ |> cf.RemapIndices(retained) get))
  }

  def retain(refs: Set[ColumnRef]): Slice = Slice(source.size, source.columns filterKeys refs) // !!! filterKeys is on-demand

  /**
    * Assumes that this and the previous slice (if any) are sorted.
    */
  def distinct(prevFilter: Option[Slice], filter: Slice): Slice = {
    lazy val retained: ArrayIntList = {
      val acc = new ArrayIntList

      def findSelfDistinct(prevRow: Int, curRow: Int) = {
        val selfComparator = rowComparatorFor(filter, filter)(_.columns.keys map (_.selector))

        @tailrec
        def findSelfDistinct0(prevRow: Int, curRow: Int): ArrayIntList = {
          if (curRow >= filter.size) acc
          else {
            val retain = selfComparator.compare(prevRow, curRow) != EQ
            if (retain) acc.add(curRow)
            findSelfDistinct0(if (retain) curRow else prevRow, curRow + 1)
          }
        }

        findSelfDistinct0(prevRow, curRow)
      }

      def findStraddlingDistinct(prev: Slice, prevRow: Int, curRow: Int) = {
        val straddleComparator = rowComparatorFor(prev, filter)(_.columns.keys map (_.selector))

        @tailrec
        def findStraddlingDistinct0(prevRow: Int, curRow: Int): ArrayIntList = {
          if (curRow >= filter.size) acc
          else {
            val retain = straddleComparator.compare(prevRow, curRow) != EQ
            if (retain) acc.add(curRow)
            if (retain)
              findSelfDistinct(curRow, curRow + 1)
            else
              findStraddlingDistinct0(prevRow, curRow + 1)
          }
        }

        findStraddlingDistinct0(prevRow, curRow)
      }

      val lastDefined = prevFilter.flatMap { slice =>
        (slice.size - 1 to 0 by -1).find(row => slice.columns.values.exists(_.isDefinedAt(row)))
      }.map {
        (prevFilter.get, _)
      }

      val firstDefined = (0 until filter.size).find(i => filter.columns.values.exists(_.isDefinedAt(i)))

      (lastDefined, firstDefined) match {
        case (Some((prev, i)), Some(j)) => findStraddlingDistinct(prev, i, j)
        case (_, Some(j))               => acc.add(j); findSelfDistinct(j, j + 1)
        case _                          => acc
      }
    }

    Slice(retained.size, source.columns lazyMapValues (_ |> cf.RemapIndices(retained) get))
  }

  def order: Ord[Int] =
    if (columns.size == 1) {
      val col = columns.head._2
      Column.rowOrder(col)
    } else {

      // The 2 cases are handled differently. In the first case, we don't have
      // any pesky homogeneous arrays and only 1 column per path. In this case,
      // we don't need to use the CPathTraversal machinery.

      type GroupedCols = Either[Map[CPath, Column], Map[CPath, Set[Column]]]

      val grouped = columns.foldLeft(Left(Map.empty): GroupedCols) {
        case (Left(acc), (ColumnRef(path, CArrayType(_)), col)) =>
          val acc0 = acc.map { case (k, v) => (k, Set(v)) }
          Right(acc0 + (path -> Set(col)))

        case (Left(acc), (ColumnRef(path, _), col)) =>
          acc get path map { col0 =>
            val acc0 = acc.map { case (k, v) => (k, Set(v)) }
            Right(acc0 + (path         -> Set(col0, col)))
          } getOrElse Left(acc + (path -> col))

        case (Right(acc), (ColumnRef(path, _), col)) =>
          Right(acc + (path -> (acc.getOrElse(path, Set.empty[Column]) + col)))
      }

      grouped match {
        case Left(cols0) =>
          val cols = cols0.toList
            .sortBy(_._1)
            .map {
              case (_, col) =>
                Column.rowOrder(col)
            }
            .toArray

          def cmp(i: Int, j: Int): Ordering = {
            var k = 0
            while (k < cols.length) {
              Order(cols(k)).order(i, j) match {
                case EQ  => k += 1
                case cmp => return cmp
              }
            }
            EQ
          }
          scalaz.Order.order(cmp _)

        case Right(cols) =>
          val paths     = cols.keys.toList
          val traversal = CPathTraversal(paths)
          traversal.rowOrder(paths, cols)
      }
    }

  def sortWith(keySlice: Slice, sortOrder: DesiredSortOrder): (Slice, Slice) = {

    // We filter out rows that are completely undefined.
    val order: Array[Int] = Array.range(0, source.size) filter { row =>
      keySlice.isDefinedAt(row) && source.isDefinedAt(row)
    }
    val rowOrder = if (sortOrder == SortAscending) keySlice.order else keySlice.order.reverseOrder
    ygg.external.MergeSort.sort(order)(rowOrder, implicitly)

    val remapOrder = new ArrayIntList(order.size)
    var i          = 0
    while (i < order.length) {
      remapOrder.add(i, order(i))
      i += 1
    }

    val sortedSlice    = source.remap(remapOrder)
    val sortedKeySlice = keySlice.remap(remapOrder)

    // TODO Remove the duplicate distinct call. Should be able to handle this in 1 pass.
    (sortedSlice.distinct(None, sortedKeySlice), sortedKeySlice.distinct(None, sortedKeySlice))
  }

  def sortBy(prefixes: Vector[CPath], sortOrder: DesiredSortOrder): Slice = {
    // TODO This is slow... Faster would require a prefix map or something... argh.
    val keySlice = Slice(source.size, prefixes.zipWithIndex.flatMap({
      case (prefix, i) =>
        source.columns collect {
          case (ColumnRef(path, tpe), col) if path hasPrefix prefix =>
            (ColumnRef(CPathIndex(i) \ path, tpe), col)
        }
    })(collection.breakOut))

    source sortWith (keySlice, sortOrder = SortAscending) _1
  }

  /**
    * Split the table at the specified index, exclusive. The
    * new prefix will contain all indices less than that index, and
    * the new suffix will contain indices >= that index.
    */
  def split(idx: Int): (Slice, Slice) = {
    (take(idx), drop(idx))
  }

  def take(sz: Int): Slice = (
    if (sz >= source.size) source
    else Slice(sz, source.columns lazyMapValues (_ |> cf.RemapFilter(_ < sz, 0) get))
  )

  def drop(sz: Int): Slice = (
    if (sz <= 0) source
    else Slice(source.size - sz, source.columns lazyMapValues (_ |> cf.RemapFilter(_ < size, sz) get))
  )

  def takeRange(start: Int, len: Int): Slice = {
    val take = math.min(size, start + len) - start
    Slice(take, source.columns lazyMapValues (_ |> cf.RemapFilter(_ < take, start) get))
  }

  def zip(other: Slice): Slice = Slice(
    source.size min other.size,
    other.columns.foldLeft(source.columns) {
      case (acc, (ref, col)) => acc.updated(ref, acc get ref flatMap (c => cf.UnionRight(c, col)) getOrElse col)
    }
  )

  /**
    * This creates a new slice with the same size and columns as this slice, but
    * whose values have been materialized and stored in arrays.
    */
  def materialized: Slice = Slice(
    source.size,
    source.columns lazyMapValues {
      case col: BoolColumn =>
        val defined = col.definedAt(0, source.size)
        val values = Bits.filteredRange(0, source.size) { row =>
          defined(row) && col(row)
        }
        ArrayBoolColumn(defined, values)

      case col: LongColumn =>
        val defined = col.definedAt(0, source.size)
        val values  = new Array[Long](source.size)
        Loop.range(0, source.size) { row =>
          if (defined(row)) values(row) = col(row)
        }
        ArrayLongColumn(defined, values)

      case col: DoubleColumn =>
        val defined = col.definedAt(0, source.size)
        val values  = new Array[Double](source.size)
        Loop.range(0, source.size) { row =>
          if (defined(row)) values(row) = col(row)
        }
        ArrayDoubleColumn(defined, values)

      case col: NumColumn =>
        val defined = col.definedAt(0, source.size)
        val values  = new Array[BigDecimal](source.size)
        Loop.range(0, source.size) { row =>
          if (defined(row)) values(row) = col(row)
        }
        ArrayNumColumn(defined, values)

      case col: StrColumn =>
        val defined = col.definedAt(0, source.size)
        val values  = new Array[String](source.size)
        Loop.range(0, source.size) { row =>
          if (defined(row)) values(row) = col(row)
        }
        ArrayStrColumn(defined, values)

      case col: DateColumn =>
        val defined = col.definedAt(0, source.size)
        val values  = new Array[DateTime](source.size)
        Loop.range(0, source.size) { row =>
          if (defined(row)) values(row) = col(row)
        }
        ArrayDateColumn(defined, values)

      case col: PeriodColumn =>
        val defined = col.definedAt(0, source.size)
        val values  = new Array[Period](source.size)
        Loop.range(0, source.size) { row =>
          if (defined(row)) values(row) = col(row)
        }
        ArrayPeriodColumn(defined, values)

      case col: EmptyArrayColumn =>
        val ncol = MutableEmptyArrayColumn.empty()
        Loop.range(0, source.size) { row =>
          ncol.update(row, col.isDefinedAt(row))
        }
        ncol

      case col: EmptyObjectColumn =>
        val ncol = MutableEmptyObjectColumn.empty()
        Loop.range(0, source.size) { row =>
          ncol.update(row, col.isDefinedAt(row))
        }
        ncol

      case col: NullColumn =>
        val ncol = MutableNullColumn.empty()
        Loop.range(0, source.size) { row =>
          ncol.update(row, col.isDefinedAt(row))
        }
        ncol

      case col =>
        abort("Cannot materialise non-standard (extensible) column")
    }
  )

  def renderJson(delimiter: String): (StreamT[M, CharBuffer], Boolean) = {
    if (columns.isEmpty) {
      (emptyStreamT[CharBuffer], false)
    } else {
      val BufferSize = 1024 * 10 // 10 KB

      val optSchema = {
        def insert(target: SchemaNode, ref: ColumnRef, col: Column): SchemaNode = {
          val ColumnRef(selector, ctype) = ref

          selector.nodes match {
            case CPathField(name) :: tail => {
              target match {
                case SchemaNode.Obj(nodes) => {
                  val subTarget = nodes get name getOrElse SchemaNode.Union(Set())
                  val result    = insert(subTarget, ColumnRef(CPath(tail), ctype), col)
                  SchemaNode.Obj(nodes + (name -> result))
                }

                case SchemaNode.Union(nodes) => {
                  val objNode = nodes find {
                    case _: SchemaNode.Obj => true
                    case _                 => false
                  }

                  val subTarget = objNode getOrElse SchemaNode.Obj(Map())
                  SchemaNode.Union(nodes - subTarget + insert(subTarget, ref, col))
                }

                case node =>
                  SchemaNode.Union(Set(node, insert(SchemaNode.Obj(Map()), ref, col)))
              }
            }

            case CPathIndex(idx) :: tail => {
              target match {
                case SchemaNode.Arr(map) => {
                  val subTarget = map get idx getOrElse SchemaNode.Union(Set())
                  val result    = insert(subTarget, ColumnRef(CPath(tail), ctype), col)
                  SchemaNode.Arr(map + (idx -> result))
                }

                case SchemaNode.Union(nodes) => {
                  val objNode = nodes find {
                    case _: SchemaNode.Arr => true
                    case _                 => false
                  }

                  val subTarget = objNode getOrElse SchemaNode.Arr(Map())
                  SchemaNode.Union(nodes - subTarget + insert(subTarget, ref, col))
                }

                case node =>
                  SchemaNode.Union(Set(node, insert(SchemaNode.Arr(Map()), ref, col)))
              }
            }

            case CPathMeta(_) :: _ => target

            case CPathArray :: _ => ???

            case Nil => {
              val node = SchemaNode.Leaf(ctype, col)

              target match {
                case SchemaNode.Union(nodes) => SchemaNode.Union(nodes + node)
                case oldNode                 => SchemaNode.Union(Set(oldNode, node))
              }
            }
          }
        }

        def normalize(schema: SchemaNode): Option[SchemaNode] = schema match {
          case SchemaNode.Obj(nodes) => {
            val nodes2 = nodes flatMap {
              case (key, value) => normalize(value) map { key -> _ }
            }

            val back =
              if (nodes2.isEmpty)
                None
              else
                Some(SchemaNode.Obj(nodes2))

            back foreach { obj =>
              obj.keys = new Array[String](nodes2.size)
              obj.values = new Array[SchemaNode](nodes2.size)
            }

            var i = 0
            back foreach { obj =>
              for ((key, value) <- nodes2) {
                obj.keys(i) = key
                obj.values(i) = value
                i += 1
              }
            }

            back
          }

          case SchemaNode.Arr(map) => {
            val map2 = map flatMap {
              case (idx, value) => normalize(value) map { idx -> _ }
            }

            val back =
              if (map2.isEmpty)
                None
              else
                Some(SchemaNode.Arr(map2))

            back foreach { arr =>
              arr.nodes = new Array[SchemaNode](map2.size)
            }

            var i = 0
            back foreach { arr =>
              val values = map2.toSeq sortBy { _._1 } map { _._2 }

              for (value <- values) {
                arr.nodes(i) = value
                i += 1
              }
            }

            back
          }

          case SchemaNode.Union(nodes) => {
            val nodes2 = nodes flatMap normalize

            if (nodes2.isEmpty)
              None
            else if (nodes2.size == 1)
              nodes2.headOption
            else {
              val union = SchemaNode.Union(nodes2)
              union.possibilities = nodes2.toArray
              Some(union)
            }
          }

          case lf: SchemaNode.Leaf => Some(lf)
        }

        val schema = columns.foldLeft(SchemaNode.Union(Set()): SchemaNode) {
          case (acc, (ref, col)) => insert(acc, ref, col)
        }

        normalize(schema)
      }

      // don't remove!  @tailrec bugs if you use optSchema.map
      if (optSchema.isDefined) {
        val schema = optSchema.get

        val depth = {
          def loop(schema: SchemaNode): Int = schema match {
            case obj: SchemaNode.Obj =>
              4 + (obj.values map loop max)

            case arr: SchemaNode.Arr =>
              2 + (arr.nodes map loop max)

            case union: SchemaNode.Union =>
              union.possibilities map loop max

            case SchemaNode.Leaf(_, _) => 0
          }

          loop(schema)
        }

        // we have the schema, now emit

        var buffer = charBuffer(BufferSize)
        val vector = new ArrayBuffer[CharBuffer](math.max(1, size / 10))

        @inline
        def checkPush(length: Int) {
          if (buffer.remaining < length) {
            buffer.flip()
            vector += buffer

            buffer = charBuffer(BufferSize)
          }
        }

        @inline
        def push(c: Char) {
          checkPush(1)
          buffer.put(c)
        }

        @inline
        def pushStr(str: String) {
          checkPush(str.length)
          buffer.put(str)
        }

        val in      = new RingDeque[String](depth + 1)
        val inFlags = new RingDeque[Boolean](depth + 1)

        @inline
        def pushIn(str: String, flag: Boolean) {
          in.pushBack(str)
          inFlags.pushBack(flag)
        }

        @inline
        def popIn() {
          in.popBack()
          inFlags.popBack()
        }

        @inline
        @tailrec
        def flushIn() {
          if (!in.isEmpty) {
            val str = in.popFront()

            val flag = inFlags.popFront()

            if (flag) {
              renderString(str)
            } else {
              checkPush(str.length)
              buffer.put(str)
            }

            flushIn()
          }
        }

        // emitters

        @inline
        @tailrec
        def renderString(str: String, idx: Int = 0) {
          if (idx == 0) {
            push('"')
          }

          if (idx < str.length) {
            val c = str.charAt(idx)

            (c: @switch) match {
              case '"'  => pushStr("\\\"")
              case '\\' => pushStr("\\\\")
              case '\b' => pushStr("\\b")
              case '\f' => pushStr("\\f")
              case '\n' => pushStr("\\n")
              case '\r' => pushStr("\\r")
              case '\t' => pushStr("\\t")

              case c => {
                if ((c >= '\u0000' && c < '\u001f') || (c >= '\u0080' && c < '\u00a0') || (c >= '\u2000' && c < '\u2100')) {
                  pushStr("\\u")
                  pushStr("%04x".format(Character.codePointAt(str, idx)))
                } else {
                  push(c)
                }
              }
            }

            renderString(str, idx + 1)
          } else {
            push('"')
          }
        }

        @inline
        def renderLong(ln: Long) {

          @inline
          @tailrec
          def power10(ln: Long, seed: Long = 1): Long = {
            // note: we could be doing binary search here

            if (seed * 10 < 0) // overflow
              seed
            else if (seed * 10 > ln)
              seed
            else
              power10(ln, seed * 10)
          }

          @inline
          @tailrec
          def renderPositive(ln: Long, power: Long) {
            if (power > 0) {
              val c = Character.forDigit((ln / power % 10).toInt, 10)
              push(c)
              renderPositive(ln, power / 10)
            }
          }

          if (ln == Long.MinValue) {
            val MinString = "-9223372036854775808"
            checkPush(MinString.length)
            buffer.put(MinString)
          } else if (ln == 0) {
            push('0')
          } else if (ln < 0) {
            push('-')

            val ln2 = ln * -1
            renderPositive(ln2, power10(ln2))
          } else {
            renderPositive(ln, power10(ln))
          }
        }

        // TODO is this a problem?
        @inline
        def renderDouble(d: Double) {
          val str = d.toString
          checkPush(str.length)
          buffer.put(str)
        }

        // TODO is this a problem?
        @inline
        def renderNum(d: BigDecimal) {
          val str = d.toString
          checkPush(str.length)
          buffer.put(str)
        }

        @inline
        def renderBoolean(b: Boolean) {
          if (b) {
            pushStr("true")
          } else {
            pushStr("false")
          }
        }

        @inline
        def renderNull() {
          pushStr("null")
        }

        @inline
        def renderEmptyObject() {
          pushStr("{}")
        }

        @inline
        def renderEmptyArray() {
          pushStr("[]")
        }

        @inline
        def renderDate(date: DateTime) {
          renderString(date.toString)
        }

        @inline
        def renderPeriod(period: Period) {
          renderString(period.toString)
        }

        @inline
        def renderArray[A](array: Array[A]) {
          renderString(array.deep.toString)
        }

        def traverseSchema(row: Int, schema: SchemaNode): Boolean = schema match {
          case obj: SchemaNode.Obj => {
            val keys   = obj.keys
            val values = obj.values

            @inline
            @tailrec
            def loop(idx: Int, done: Boolean): Boolean = {
              if (idx < keys.length) {
                val key   = keys(idx)
                val value = values(idx)

                if (done) {
                  pushIn(",", false)
                }

                pushIn(key, true)
                pushIn(":", false)

                val emitted = traverseSchema(row, value)

                if (!emitted) { // less efficient
                  popIn()
                  popIn()

                  if (done) {
                    popIn()
                  }
                }

                loop(idx + 1, done || emitted)
              } else {
                done
              }
            }

            pushIn("{", false)
            val done = loop(0, false)

            if (done) {
              push('}')
            } else {
              popIn()
            }

            done
          }

          case arr: SchemaNode.Arr => {
            val values = arr.nodes

            @inline
            @tailrec
            def loop(idx: Int, done: Boolean): Boolean = {
              if (idx < values.length) {
                val value = values(idx)

                if (done) {
                  pushIn(",", false)
                }

                val emitted = traverseSchema(row, value)

                if (!emitted && done) { // less efficient
                  popIn()
                }

                loop(idx + 1, done || emitted)
              } else {
                done
              }
            }

            pushIn("[", false)
            val done = loop(0, false)

            if (done) {
              push(']')
            } else {
              popIn()
            }

            done
          }

          case union: SchemaNode.Union => {
            val pos = union.possibilities

            @inline
            @tailrec
            def loop(idx: Int): Boolean = {
              if (idx < pos.length) {
                traverseSchema(row, pos(idx)) || loop(idx + 1)
              } else {
                false
              }
            }

            loop(0)
          }

          case SchemaNode.Leaf(tpe, col) =>
            tpe match {
              case CString => {
                val specCol = col.asInstanceOf[StrColumn]

                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderString(specCol(row))
                  true
                } else {
                  false
                }
              }

              case CBoolean => {
                val specCol = col.asInstanceOf[BoolColumn]

                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderBoolean(specCol(row))
                  true
                } else {
                  false
                }
              }

              case CLong => {
                val specCol = col.asInstanceOf[LongColumn]

                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderLong(specCol(row))
                  true
                } else {
                  false
                }
              }

              case CDouble => {
                val specCol = col.asInstanceOf[DoubleColumn]

                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderDouble(specCol(row))
                  true
                } else {
                  false
                }
              }

              case CNum => {
                val specCol = col.asInstanceOf[NumColumn]

                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderNum(specCol(row))
                  true
                } else {
                  false
                }
              }

              case CNull => {
                val specCol = col.asInstanceOf[NullColumn]
                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderNull()
                  true
                } else {
                  false
                }
              }

              case CEmptyObject => {
                val specCol = col.asInstanceOf[EmptyObjectColumn]
                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderEmptyObject()
                  true
                } else {
                  false
                }
              }

              case CEmptyArray => {
                val specCol = col.asInstanceOf[EmptyArrayColumn]
                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderEmptyArray()
                  true
                } else {
                  false
                }
              }

              case CDate => {
                val specCol = col.asInstanceOf[DateColumn]

                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderDate(specCol(row))
                  true
                } else {
                  false
                }
              }

              case CPeriod => {
                val specCol = col.asInstanceOf[PeriodColumn]

                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderPeriod(specCol(row))
                  true
                } else {
                  false
                }
              }

              case CArrayType(_) => {
                val specCol = col.asInstanceOf[HomogeneousArrayColumn[_]]

                if (specCol.isDefinedAt(row)) {
                  flushIn()
                  renderArray(specCol(row))
                  true
                } else {
                  false
                }
              }

              case CUndefined => false
            }
        }

        @tailrec
        def render(row: Int, delimit: Boolean): Boolean = {
          if (row < size) {
            if (delimit) {
              pushIn(delimiter, false)
            }

            val rowRendered = traverseSchema(row, schema)

            if (delimit && !rowRendered) {
              popIn()
            }

            render(row + 1, delimit || rowRendered)
          } else {
            delimit
          }
        }

        val rendered = render(0, false)

        buffer.flip()
        vector += buffer

        val stream = StreamT.unfoldM(0) { idx =>
          Need(
            if (idx < vector.length)
              Some((vector(idx), idx + 1))
            else
              None
          )
        }

        (stream, rendered)
      } else emptyStreamT[CharBuffer] -> false
    }
  }

  def toRValue(row: Int): RValue = {
    columns.foldLeft[RValue](CUndefined) {
      case (rv, (ColumnRef(selector, _), col)) if col.isDefinedAt(row) => rv.unsafeInsert(selector, col.cValue(row))
      case (rv, _)                                                     => rv
    }
  }

  def toJValue(row: Int) = {
    columns.foldLeft[JValue](JUndefined) {
      case (jv, (ColumnRef(selector, _), col)) if col.isDefinedAt(row) =>
        cPathToJPaths(selector, col.cValue(row)).foldLeft(jv) {
          case (jv, (path, value)) => jv.unsafeInsert(path, value.toJValue)
        }

      case (jv, _) => jv
    }
  }

  def toJson(row: Int): Option[JValue] = toJValue(row) match {
    case JUndefined => None
    case jv         => Some(jv)
  }

  def toJsonElements: Vector[JValue] = {
    @tailrec def rec(i: Int, acc: Vector[JValue]): Vector[JValue] = {
      if (i < source.size) {
        toJValue(i) match {
          case JUndefined => rec(i + 1, acc)
          case jv         => rec(i + 1, acc :+ jv)
        }
      } else acc
    }

    rec(0, Vector())
  }

  def toString(row: Int): Option[String] = {
    (columns.toList.sortBy(_._1) map { case (ref, col) => ref.toString + ": " + (if (col.isDefinedAt(row)) col.strValue(row) else "(undefined)") }) match {
      case Nil                                         => None
      case l                                           => Some(l.mkString("[", ", ", "]"))
    }
  }

  def toJsonString(prefix: String = ""): String = {
    (0 until size).map(i => prefix + " " + toJson(i)).mkString("\n")
  }

  def cPathToJPaths(cpath: CPath, value: CValue): List[JPath -> CValue] = {
    import ygg.json._

    def add(c: JPathNode, xs: List[JPath -> CValue]): List[JPath -> CValue] =
      xs map { case (path, value) => (JPath(c :: path.nodes), value) }

    (cpath.nodes, value) match {
      case (Nil, _)                            => List(NoJPath -> value)
      case (CPathField(name) :: tail, _)       => add(JPathField(name), cPathToJPaths(CPath(tail), value))
      case (CPathIndex(i) :: tail, _)          => add(JPathIndex(i), cPathToJPaths(CPath(tail), value))
      case (CPathArray :: tail, es: CArray[_]) =>
        val CArrayType(elemType) = es.cType
        es.value.toList.zipWithIndex flatMap { case (e, i) => add(JPathIndex(i), cPathToJPaths(CPath(tail), elemType(e))) }
      case (path, _) => abort("Bad news, bob! " + path)
    }
  }

  override def toString = (0 until size).map(toString(_).getOrElse("")).mkString("\n")
}

object Slice {
  implicit def sliceOps(s: Slice): SliceOps           = new SliceOps(s)
  implicit def derefSliceOps(s: Slice): DerefSliceOps = new DerefSliceOps(s)

  def empty: Slice                                = apply(0, Map())
  def apply(size: Int, columns: ColumnMap): Slice = new DirectSlice(size, columns)
  def apply(pair: ColumnMap -> Int): Slice        = apply(pair._2, pair._1)

  def updateRefs(rv: RValue, into: Map[ColumnRef, ArrayColumn[_]], sliceIndex: Int, sliceSize: Int): Map[ColumnRef, ArrayColumn[_]] = {
    rv.flattenWithPath.foldLeft(into) {
      case (acc, (cpath, CUndefined)) => acc
      case (acc, (cpath, cvalue)) =>
        val ref = ColumnRef(cpath, (cvalue.cType))

        val updatedColumn: ArrayColumn[_] = cvalue match {
          case CBoolean(b) =>
            acc.getOrElse(ref, ArrayBoolColumn.empty()).asInstanceOf[ArrayBoolColumn].unsafeTap { c =>
              c.update(sliceIndex, b)
            }

          case CLong(d) =>
            acc.getOrElse(ref, ArrayLongColumn.empty(sliceSize)).asInstanceOf[ArrayLongColumn].unsafeTap { c =>
              c.update(sliceIndex, d.toLong)
            }

          case CDouble(d) =>
            acc.getOrElse(ref, ArrayDoubleColumn.empty(sliceSize)).asInstanceOf[ArrayDoubleColumn].unsafeTap { c =>
              c.update(sliceIndex, d.toDouble)
            }

          case CNum(d) =>
            acc.getOrElse(ref, ArrayNumColumn.empty(sliceSize)).asInstanceOf[ArrayNumColumn].unsafeTap { c =>
              c.update(sliceIndex, d)
            }

          case CString(s) =>
            acc.getOrElse(ref, ArrayStrColumn.empty(sliceSize)).asInstanceOf[ArrayStrColumn].unsafeTap { c =>
              c.update(sliceIndex, s)
            }

          case CDate(d) =>
            acc.getOrElse(ref, ArrayDateColumn.empty(sliceSize)).asInstanceOf[ArrayDateColumn].unsafeTap { c =>
              c.update(sliceIndex, d)
            }

          case CPeriod(p) =>
            acc.getOrElse(ref, ArrayPeriodColumn.empty(sliceSize)).asInstanceOf[ArrayPeriodColumn].unsafeTap { c =>
              c.update(sliceIndex, p)
            }

          case CArray(arr, cType) =>
            acc.getOrElse(ref, ArrayHomogeneousArrayColumn.empty(sliceSize)(cType)).asInstanceOf[ArrayHomogeneousArrayColumn[cType.tpe]].unsafeTap { c =>
              c.update(sliceIndex, arr)
            }

          case CEmptyArray =>
            acc.getOrElse(ref, MutableEmptyArrayColumn.empty()).asInstanceOf[MutableEmptyArrayColumn].unsafeTap { c =>
              c.update(sliceIndex, true)
            }

          case CEmptyObject =>
            acc.getOrElse(ref, MutableEmptyObjectColumn.empty()).asInstanceOf[MutableEmptyObjectColumn].unsafeTap { c =>
              c.update(sliceIndex, true)
            }

          case CNull =>
            acc.getOrElse(ref, MutableNullColumn.empty()).asInstanceOf[MutableNullColumn].unsafeTap { c =>
              c.update(sliceIndex, true)
            }
          case x =>
            abort(s"Unexpected arg $x")
        }

        acc + (ref -> updatedColumn)
    }
  }

  def fromJValues(values: Stream[JValue]): Slice = fromRValues(values.map(RValue.fromJValue))

  def fromRValues(values: Stream[RValue]): Slice = {
    val sliceSize = values.size

    @tailrec def buildColArrays(from: Stream[RValue], into: Map[ColumnRef, ArrayColumn[_]], sliceIndex: Int): (Map[ColumnRef, ArrayColumn[_]], Int) = {
      from match {
        case jv #:: xs =>
          val refs = updateRefs(jv, into, sliceIndex, sliceSize)
          buildColArrays(xs, refs, sliceIndex + 1)
        case _ =>
          (into, sliceIndex)
      }
    }

    Slice(buildColArrays(values, Map.empty[ColumnRef, ArrayColumn[_]], 0))
  }

  /**
    * Concatenate multiple slices into 1 big slice. The slices will be
    * concatenated in the order they appear in `slices`.
    */
  def concat(slices: Seq[Slice]): Slice = {
    val (_columns, _size) = slices.foldLeft((Map.empty[ColumnRef, List[Int -> Column]], 0)) {
      case ((cols, offset), slice) if slice.size > 0 =>
        (slice.columns.foldLeft(cols) {
          case (acc, (ref, col)) =>
            acc + (ref -> ((offset, col) :: acc.getOrElse(ref, Nil)))
        }, offset + slice.size)

      case ((cols, offset), _) => (cols, offset)
    }

    Slice(_size, _columns flatMap { case (ref, parts) => NConcat(parts) map (ref -> _) })
  }

  def rowComparatorFor(s1: Slice, s2: Slice)(keyf: Slice => Iterable[CPath]): RowComparator = {
    val paths     = (keyf(s1) ++ keyf(s2)).toList
    val traversal = CPathTraversal(paths)
    val lCols     = s1.columns groupBy (_._1.selector) map { case (path, m) => path -> m.values.toSet }
    val rCols     = s2.columns groupBy (_._1.selector) map { case (path, m) => path -> m.values.toSet }
    val allPaths  = (lCols.keys ++ rCols.keys).toList
    val order     = traversal.rowOrder(allPaths, lCols, Some(rCols))
    new RowComparator {
      def compare(r1: Int, r2: Int): Ordering = order.order(r1, r2)
    }
  }

  /**
    * Given a JValue, an existing map of columnrefs to column data,
    * a sliceIndex, and a sliceSize, return an updated map.
    */
  def withIdsAndValues(jv: JValue, into: Map[ColumnRef, ArrayColumn[_]], sliceIndex: Int, sliceSize: Int): Map[ColumnRef, ArrayColumn[_]] = {
    jv.flattenWithPath.foldLeft(into) {
      case (acc, (jpath, JUndefined)) => acc
      case (acc, (jpath, v)) =>
        val ctype = CType.forJValue(v) getOrElse { abort("Cannot determine ctype for " + v + " at " + jpath + " in " + jv) }
        val ref   = ColumnRef(CPath(jpath), ctype)

        val updatedColumn: ArrayColumn[_] = v match {
          case JBool(b) =>
            acc.getOrElse(ref, ArrayBoolColumn.empty()).asInstanceOf[ArrayBoolColumn].unsafeTap { c =>
              c.update(sliceIndex, b)
            }

          case JNum(d) =>
            ctype match {
              case CLong =>
                acc.getOrElse(ref, ArrayLongColumn.empty(sliceSize)).asInstanceOf[ArrayLongColumn].unsafeTap { c =>
                  c.update(sliceIndex, d.toLong)
                }

              case CDouble =>
                acc.getOrElse(ref, ArrayDoubleColumn.empty(sliceSize)).asInstanceOf[ArrayDoubleColumn].unsafeTap { c =>
                  c.update(sliceIndex, d.toDouble)
                }

              case CNum =>
                acc.getOrElse(ref, ArrayNumColumn.empty(sliceSize)).asInstanceOf[ArrayNumColumn].unsafeTap { c =>
                  c.update(sliceIndex, d)
                }

              case _ => abort("non-numeric type reached")
            }

          case JString(s) =>
            acc.getOrElse(ref, ArrayStrColumn.empty(sliceSize)).asInstanceOf[ArrayStrColumn].unsafeTap { c =>
              c.update(sliceIndex, s)
            }

          case JArray(Nil) =>
            acc.getOrElse(ref, MutableEmptyArrayColumn.empty()).asInstanceOf[MutableEmptyArrayColumn].unsafeTap { c =>
              c.update(sliceIndex, true)
            }

          case JObject.empty =>
            acc.getOrElse(ref, MutableEmptyObjectColumn.empty()).asInstanceOf[MutableEmptyObjectColumn].unsafeTap { c =>
              c.update(sliceIndex, true)
            }

          case JNull =>
            acc.getOrElse(ref, MutableNullColumn.empty()).asInstanceOf[MutableNullColumn].unsafeTap { c =>
              c.update(sliceIndex, true)
            }

          case _ => abort("non-flattened value reached")
        }

        acc + (ref -> updatedColumn)
    }
  }

  private[table] sealed trait SchemaNode

  private[table] object SchemaNode {
    final case class Obj(nodes: Map[String, SchemaNode]) extends SchemaNode {
      final var keys: Array[String]       = _
      final var values: Array[SchemaNode] = _
    }

    final case class Arr(map: Map[Int, SchemaNode]) extends SchemaNode {
      final var nodes: Array[SchemaNode] = _
    }

    final case class Union(nodes: Set[SchemaNode]) extends SchemaNode {
      final var possibilities: Array[SchemaNode] = _
    }

    final case class Leaf(tpe: CType, col: Column) extends SchemaNode
  }
}
