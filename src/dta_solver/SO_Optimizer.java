package dta_solver;

import java.util.Iterator;
import java.util.Map.Entry;

import generalLWRNetwork.Cell;
import generalLWRNetwork.Destination;
import generalLWRNetwork.Origin;
import generalNetwork.state.CellInfo;
import generalNetwork.state.Profile;
import generalNetwork.state.externalSplitRatios.IntertemporalOriginsSplitRatios;

import org.apache.commons.math3.optimization.DifferentiableMultivariateOptimizer;
import org.wsj.AdjointForJava;

import scala.Option;
import scala.Some;

import cern.colt.matrix.tdouble.DoubleMatrix1D;
import cern.colt.matrix.tdouble.impl.SparseCCDoubleMatrix2D;
import cern.colt.matrix.tdouble.impl.SparseDoubleMatrix1D;
import cern.colt.matrix.tdouble.DoubleFactory1D;

public class SO_Optimizer extends AdjointForJava<Simulator> {

  private Simulator simulation;

  private double epsilon = 0.02;

  /* Total number of time steps */
  private int T;
  /* Number of compliant commodities */
  private int C;
  private Cell[] cells;
  private Origin[] sources;
  /* Number of origins */
  private int O;
  private Destination[] destinations;
  /* Number of destinations */
  private int S;

  /* Size of a block for one time step of the control */
  private int temporal_control_block_size;

  public SO_Optimizer(DifferentiableMultivariateOptimizer op, int maxIter,
      Simulator simu) {
    super(op, maxIter);
    simulation = simu;

    T = simulation.time_discretization.getNb_steps();
    C = simulation.lwr_network.getNb_compliantCommodities();
    cells = simulation.lwr_network.getCells();

    sources = simulation.lwr_network.getSources();
    O = sources.length;
    Destination[] destinations = simulation.lwr_network.getSinks();
    S = destinations.length;

    /*
     * For every time steps there are C compliant flows, and O non compliant
     */
    temporal_control_block_size = (C + O);
  }

  public double[] getControl() {

    IntertemporalOriginsSplitRatios splits = simulation.splits;

    /*
     * For every time steps there are C compliant flows, and O non compliant
     * There is also the sum of the split ratios for every origins
     */
    double[] control = new double[T * temporal_control_block_size];

    int index_in_control = 0;
    int commodity;
    Double split_ratio;
    for (int orig = 0; orig < O; orig++) {
      double[] sum_of_split_ratios = new double[T];
      for (int k = 0; k < T; k++) {
        /*
         * Mapping between
         * splits.get(sources[orig], k).get(0) 
         * and U[k*(C + sources.length + index_in_control)]
         */
        split_ratio = splits.get(sources[orig], k).get(0);
        if (split_ratio != null) {
          control[k * temporal_control_block_size + index_in_control] = split_ratio;
          sum_of_split_ratios[k] += split_ratio;
        }
      }
      index_in_control++;

      Iterator<Integer> it = sources[orig]
          .getCompliant_commodities()
          .iterator();
      while (it.hasNext()) {
        commodity = it.next();
        for (int k = 0; k < T; k++) {
          /*
           * Mapping between
           * splits.get(sources[orig], k).get(commodity) and
           * U[k*(C +sources.length) + index_in_control]
           */
          split_ratio = splits.get(sources[orig], k).get(commodity);
          if (split_ratio != null) {
            control[k * temporal_control_block_size + index_in_control] = split_ratio;
            sum_of_split_ratios[k] += split_ratio;
          }
        }
        index_in_control++;

      }
      /* At the end we add the sum of the split ratios at that origin */
      for (int k = 0; k < T; k++)
        sources[orig].sum_split_ratios = sum_of_split_ratios;
    }

    return control;
  }

  public void parseStateVector(Profile p) {

    /* Size of the description of a profile for a given time step */
    int block_size = (3 * (C + 1) + 2) * cells.length;
    /* Size of a block describing all the densities for a given time step */
    int size_density_block = cells.length * (C + 1);
    /* Size of a block describing all the supply/demand at one time step */
    int size_demand_suply_block = 2 * cells.length;
    /* Size of a block describing out-flows */
    int size_f_out_block = size_density_block;

    int block_id, sub_block_id;
    int commodity;
    int index_in_state = 0;
    double value;
    CellInfo cell_info;
    for (int k = 0; k < T; k++) {
      /* Id of the first data of time step k */
      block_id = k * block_size;

      for (int cell_id = 0; cell_id < cells.length; cell_id++) {

        cell_info = p.get(cells[cell_id]);
        /* Id of the first index containing data from cells[cell_id] */
        sub_block_id = block_id + cell_id * C;

        // Operations on densities
        Iterator<Entry<Integer, Double>> it =
            cell_info.partial_densities.entrySet().iterator();
        Entry<Integer, Double> entry;
        while (it.hasNext()) {
          entry = it.next();
          commodity = entry.getKey();
          // density (cell_id, commodity)(k)
          index_in_state = sub_block_id + commodity;
          value = entry.getValue();
        }

        // Operations on demand and supply
        index_in_state = sub_block_id + size_density_block;
        value = cell_info.demand;
        index_in_state++;
        value = cell_info.supply;

        // Operation on out-flows
        sub_block_id += size_demand_suply_block;
        it = cell_info.out_flows.entrySet().iterator();
        while (it.hasNext()) {
          entry = it.next();
          commodity = entry.getKey();
          // flow_out (cell_id, commodity)(k)
          index_in_state = sub_block_id + commodity;
          value = entry.getValue();
        }

        // Operations on in-flows
        index_in_state += size_f_out_block;
        it = cell_info.in_flows.entrySet().iterator();
        while (it.hasNext()) {
          entry = it.next();
          commodity = entry.getKey();
          // flow_in (cell_id, commodity)(k)
          index_in_state = sub_block_id + commodity;
          value = entry.getValue();
        }
      }
    }
  }

  @Override
  public Option<SparseCCDoubleMatrix2D> dhdu(Simulator arg0,
      double[] arg1) {
    // TODO Auto-generated method stub
    // // Some<Double> d = new Some<Double>(Double.valueOf(1));
    return null;
  }

  @Override
  public Option<SparseCCDoubleMatrix2D> dhdx(DTA_ParallelSimulator arg0,
      double[] arg1) {
    // TODO Auto-generated method stub
    return null;
  }

  /**
   * @brief Computes the derivative dJ/dU
   */
  @Override
  public DoubleMatrix1D djdu(Simulator simulator, double[] control) {

    IntertemporalOriginsSplitRatios splits = simulator.splits;

    /* For every time steps there are C compliant flows, and O non compliant */
    DoubleMatrix1D result =
        DoubleFactory1D.dense.make(T * temporal_control_block_size);

    int index_in_control = 0;
    Double split_ratio;
    double[] sum_of_split_ratios = new double[T];
    double partial_sum;

    for (int orig = 0; orig < O; orig++) {
      /*
       * First we compute the sum of the split ratios for this origin for all
       * time steps to be able to put the barrier \sum \beta_o(k) >= 1
       */
      for (int k = 0; k < T; k++) {
        partial_sum = 0;

        int nb_commodities = sources[orig]
            .getCompliant_commodities()
            .size() + 1;
        for (int tmp = 0; tmp < nb_commodities; tmp++) {
          /*
           * k * (C+O) is the beginning of the block for time step k
           * index_in_control gives the position of the sub-block for the given
           * origin
           * tmp iterates over 1 to (nb_commodities)
           */
          partial_sum +=
              control[k * temporal_control_block_size
                  + sources[orig].getUniqueId() * (C + 1) + tmp];
        }
        sum_of_split_ratios[k] = partial_sum;
      }
    }
    for (int orig = 0; orig < O; orig++) {

      for (int k = 0; k < T; k++) {
        /*
         * Mapping between
         * splits.get(sources[orig], k).get(0) and U[k*(C + sources.length)]
         */
        /* In case of full System Optimal computation we skip the NC flows */
        split_ratio = splits.get(sources[orig], k).get(0);
        if (split_ratio == null || split_ratio == 0) {
          continue;
        }

        double derivative_term = 0;
        if (control[k * temporal_control_block_size] == 0) {
          System.out.println("!FAILURE! A non compliant split ratio is ZERO !");
          assert false;
        } else {
          /*
           * The condition \beta >= 0 is already put in the solver (in
           * AdjointJVM/org.wsj/Optimizers.scala)
           */
          // derivative_term -= epsilon1 / control[k * (C + O)];
        }
        System.out.println("NCF OK");

        if (sum_of_split_ratios[k] == 0) {
          System.out
              .println("!Warning! Sum of the split ratios for an origin is Zero !");
          assert false;
        } else {
          // TODO : For now we imposes \sum \beta > 0.999
          assert sum_of_split_ratios[k] >= 0.999;
          // To skip one operation we do 1 / (a-b) instead of - 1 / (b-a)
          derivative_term += epsilon / (0.999 - sum_of_split_ratios[k]);
        }

        result.set(k * temporal_control_block_size, derivative_term);
      }
      index_in_control++;

      Iterator<Integer> it = sources[orig]
          .getCompliant_commodities()
          .iterator();
      while (it.hasNext()) {
        it.next(); // Needed to empty the iterator
        for (int k = 0; k < T; k++) {
          /*
           * Mapping between
           * splits.get(sources[orig], k).get(commodity) and
           * U[k*(C +sources.length) + index_in_control]
           */
          double derivative_term = 0;

          // The >= 0 constraint is already in the solver
          /*
           * if (c == 0) {
           * System.out.println("!FAILURE! A non compliant split ratio is ZERO !"
           * );
           * assert false;
           * } else {
           * 
           * }
           */

          if (sum_of_split_ratios[k] == 0) {
            System.out
                .println("!Warning! Sum of the split ratios for an origin is Zero !");
            assert false;
          } else {
            // TODO : For now we imposes \sum \beta > 0.999
            assert sum_of_split_ratios[k] >= 0.999;
            derivative_term += epsilon / (0.999 - sum_of_split_ratios[k]);
          }
          result.set(k * temporal_control_block_size
              + index_in_control, derivative_term);
        }
        index_in_control++;
      }
    }

    return result;
  }

  /**
   * @brief Computes the dJ/dX matrix
   * @details All terms are zero except the ones which are partial derivative in
   *          a partial density
   */
  @Override
  public SparseDoubleMatrix1D djdx(Simulator simulator, double[] arg1) {

    /* Size of the description of a profile for a given time step */
    int block_size = (3 * (C + 1) + 2) * cells.length;
    /* Size of a block describing all the densities for a given time step */
    int size_density_block = cells.length * (C + 1);

    SparseDoubleMatrix1D result = new SparseDoubleMatrix1D(T * block_size);

    /* We put 1 when we derivate along a partial density */
    int block_position;
    for (int k = 0; k < T; k++) {
      block_position = k * block_size;
      for (int partial_density_id = 0; partial_density_id < size_density_block; partial_density_id++) {
        result.setQuick(block_position + partial_density_id, 1.0);
      }
    }

    return result;
  }

  /**
   * @brief Forward simulate after having loaded the external split ratios
   * @details For now we even put the null split ratios because we never clear
   *          the split ratios
   */
  @Override
  public Simulator forwardSimulate(double[] control) {

    IntertemporalOriginsSplitRatios splits = simulation.splits;

    int index_in_control = 0;
    int commodity;
    for (int orig = 0; orig < O; orig++) {
      double[] sum_of_split_ratios = new double[T];
      for (int k = 0; k < T; k++) {
        /*
         * Mapping between
         * splits.get(sources[orig], k).get(0)
         * and U[k*(C + sources.length + index_in_control)]
         */
        splits.get(sources[orig], k)
            .put(0,
                control[k * temporal_control_block_size + index_in_control]);
        sum_of_split_ratios[k] +=
            control[k * temporal_control_block_size + index_in_control];

      }
      index_in_control++;

      Iterator<Integer> it = sources[orig]
          .getCompliant_commodities()
          .iterator();
      while (it.hasNext()) {
        commodity = it.next();
        for (int k = 0; k < T; k++) {
          /*
           * Mapping between
           * splits.get(sources[orig], k).get(commodity) and
           * U[k*(C +sources.length) + index_in_control]
           */
          splits.get(sources[orig], k).
              put(commodity,
                  control[k * temporal_control_block_size + index_in_control]);
          sum_of_split_ratios[k] +=
              control[k * temporal_control_block_size + index_in_control];
        }
        index_in_control++;
      }
      /* At the end we add the sum of the split ratios at that origin */
      for (int k = 0; k < T; k++)
        sources[orig].sum_split_ratios = sum_of_split_ratios;
    }

    simulation.run(false);
    return simulation;
  }

  /**
   * @brief Computes the objective function:
   *        \sum_(i,c,k) \rho(i,c,k)
   *        - \sum_{origin o} epsilon2 * ln(\sum \rho(o,c,k) - 1)
   */
  @Override
  public double objective(Simulator simulator, double[] control) {
    double objective = 0;

    for (int k = 0; k < T; k++)
      for (int cell_id = 0; cell_id < cells.length; cell_id++)
        objective += simulator.profiles[k].get(cell_id).total_density;

    for (int orig = 0; orig < O; orig++)
      for (int k = 0; k < T; k++)
        objective -=
            epsilon * Math.log(sources[orig].sum_split_ratios[k] - 0.999);

    return objective;
  }
}
