package org.opentrackingtools.updater;

import gov.sandia.cognition.math.matrix.MatrixFactory;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.bayesian.ParticleFilter.Updater;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;
import gov.sandia.cognition.util.AbstractCloneableSerializable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.opentrackingtools.VehicleStateInitialParameters;
import org.opentrackingtools.distributions.CountedDataDistribution;
import org.opentrackingtools.distributions.OnOffEdgeTransDistribution;
import org.opentrackingtools.distributions.PathStateDistribution;
import org.opentrackingtools.distributions.PathStateMixtureDensityModel;
import org.opentrackingtools.distributions.TruncatedRoadGaussian;
import org.opentrackingtools.estimators.MotionStateEstimatorPredictor;
import org.opentrackingtools.graph.InferenceGraph;
import org.opentrackingtools.graph.InferenceGraphEdge;
import org.opentrackingtools.graph.InferenceGraphSegment;
import org.opentrackingtools.model.GpsObservation;
import org.opentrackingtools.model.SimpleBayesianParameter;
import org.opentrackingtools.model.VehicleStateDistribution;
import org.opentrackingtools.model.VehicleStateDistribution.VehicleStateDistributionFactory;
import org.opentrackingtools.paths.Path;
import org.opentrackingtools.paths.PathEdge;
import org.opentrackingtools.paths.PathState;
import org.opentrackingtools.util.PathUtils;
import org.opentrackingtools.util.SvdMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * This is an updater that produces new states from paths generated by sampling
 * edges along the predicted motion state.
 * 
 * @author bwillard
 * 
 * @param <O>
 */
public class VehicleStateBootstrapUpdater<O extends GpsObservation>
    extends AbstractCloneableSerializable implements
    Updater<O, VehicleStateDistribution<O>> {

  protected static class GraphPath {
    protected List<InferenceGraphSegment> edges;
    protected double length;

    public GraphPath(GraphPath path) {
      this.length = path.length;
      this.edges = Lists.newArrayList(path.getEdges());
    }

    public GraphPath(InferenceGraphSegment startEdge) {
      this.edges = Lists.newArrayList(startEdge);
      this.length = startEdge.getLength();
    }

    public void addEdge(InferenceGraphSegment edge) {
      this.edges.add(edge);
      this.length += edge.getLength();
    }

    public List<InferenceGraphSegment> getEdges() {
      return this.edges;
    }

    public double getLength() {
      return this.length;
    }

  }

  private static final Logger _log = LoggerFactory
      .getLogger(VehicleStateBootstrapUpdater.class);

  protected static final long maxGraphBoundsResampleTries =
      (long) 1e6;

  private static final long serialVersionUID = 2884138088944317656L;

  protected InferenceGraph inferenceGraph;

  protected O initialObservation;

  protected VehicleStateInitialParameters parameters;

  protected Random random;

  /*
   * The error added during the last processed transition.  For debug, really.
   */
  protected Vector sampledTransitionError;

  public long seed;

  protected VehicleStateDistributionFactory<O, InferenceGraph> vehicleStateFactory;

  public VehicleStateBootstrapUpdater(O obs,
    InferenceGraph inferredGraph,
    VehicleStateInitialParameters parameters, Random rng) {
    this.initialObservation = obs;
    this.inferenceGraph = inferredGraph;
    if (rng == null) {
      this.random = new Random();
    } else {
      this.random = rng;
    }
    this.parameters = parameters;
    this.vehicleStateFactory =
        new VehicleStateDistribution.VehicleStateDistributionFactory<O, InferenceGraph>();
  }

  @Override
  public double computeLogLikelihood(
    VehicleStateDistribution<O> particle, O observation) {
    double logLikelihood = 0d;
    logLikelihood +=
        particle.getMotionStateParam().getConditionalDistribution()
            .getProbabilityFunction()
            .logEvaluate(observation.getProjectedPoint());
    return logLikelihood;
  }

  /**
   * Create vehicle states from the nearby edges.
   */
  @Override
  public DataDistribution<VehicleStateDistribution<O>>
      createInitialParticles(int numParticles) {
    final DataDistribution<VehicleStateDistribution<O>> retDist =
        new CountedDataDistribution<VehicleStateDistribution<O>>(true);

    /*
     * Start by creating an off-road vehicle state with which we can obtain the surrounding
     * edges.
     * 
     * TODO
     * In the bootstrap filter we aren't dealing with the prior distributions
     * so remove them?
     */

    final VehicleStateDistribution<O> nullState =
        this.vehicleStateFactory.createInitialVehicleState(
            this.parameters, this.inferenceGraph,
            this.initialObservation, this.random,
            PathEdge.nullPathEdge);
    final MultivariateGaussian initialMotionStateDist =
        nullState.getMotionStateParam().getParameterPrior();
    final Collection<InferenceGraphSegment> edges =
        this.inferenceGraph.getNearbyEdges(initialMotionStateDist,
            initialMotionStateDist.getCovariance());

    for (int i = 0; i < numParticles; i++) {
      /*
       * From the surrounding edges, we create states on those edges.
       */
      final DataDistribution<VehicleStateDistribution<O>> statesOnEdgeDistribution =
          new CountedDataDistribution<VehicleStateDistribution<O>>(
              true);

      final double nullLogLikelihood =
          nullState.getEdgeTransitionParam()
              .getConditionalDistribution().getProbabilityFunction()
              .logEvaluate(InferenceGraphEdge.nullGraphEdge)
              + this.computeLogLikelihood(nullState,
                  this.initialObservation);

      statesOnEdgeDistribution
          .increment(nullState, nullLogLikelihood);

      for (final InferenceGraphSegment line : edges) {

        final PathEdge startPathEdge = new PathEdge(line, 0d, false);
        final VehicleStateDistribution<O> stateOnEdge =
            this.vehicleStateFactory.createInitialVehicleState(
                this.parameters, this.inferenceGraph,
                this.initialObservation, this.random, startPathEdge);

        final double logLikelihood =
            stateOnEdge.getEdgeTransitionParam()
                .getConditionalDistribution()
                .getProbabilityFunction()
                .logEvaluate(startPathEdge.getInferenceGraphSegment())
                + this.computeLogLikelihood(stateOnEdge,
                    this.initialObservation);

        statesOnEdgeDistribution
            .increment(stateOnEdge, logLikelihood);
      }

      retDist.increment(statesOnEdgeDistribution.sample(this.random));
    }

    return retDist;
  }

  private List<GraphPath> getPathsUpToLength(GraphPath startGraph,
    double lengthToTravel) {

    /*
     * This seems like too much to compute...
     */
    Preconditions.checkArgument(lengthToTravel < 1000d);

    final InferenceGraphSegment startEdge =
        Iterables.getLast(startGraph.getEdges());
    if (startEdge.getLength() < lengthToTravel && lengthToTravel > 0d) {
      final Collection<InferenceGraphSegment> transferEdges =
          this.inferenceGraph.getOutgoingTransferableEdges(startEdge);
      if (transferEdges.isEmpty()) {
        /*
         * No transfers and didn't meet the length requirement.
         * Abandon.
         */
        return Collections.emptyList();
      } else {
        /*
         * Still traversing...
         */
        final List<GraphPath> newPaths = Lists.newArrayList();
        for (final InferenceGraphSegment edge : transferEdges) {
          final GraphPath newPath = new GraphPath(startGraph);
          newPath.addEdge(edge);
          newPaths.addAll(this.getPathsUpToLength(newPath,
              lengthToTravel - edge.getLength()));
        }
        return newPaths;
      }
    } else {
      /*
       * Found the length!
       */
      return Collections.singletonList(startGraph);
    }
  }

  public Vector getSampledTransitionError() {
    return this.sampledTransitionError;
  }

  public void setRandom(Random rng) {
    this.random = rng;
  }

  public void
      setSampledTransitionError(Vector sampledTransitionError) {
    this.sampledTransitionError = sampledTransitionError;
  }

  @Override
  public VehicleStateDistribution<O> update(
    VehicleStateDistribution<O> previousState) {

    final VehicleStateDistribution<O> updatedState =
        previousState.clone();
    final MotionStateEstimatorPredictor motionStatePredictor =
        new MotionStateEstimatorPredictor(updatedState, this.random,
            this.parameters.getInitialObsFreq());

    /*
     * Predict new location, i.e. project forward
     */
    MultivariateGaussian predictedMotionState =
        motionStatePredictor
            .createPredictiveDistribution(updatedState
                .getMotionStateParam().getParameterPrior());
    /*
     * Add some transition error and set this as the
     * new motion state for our new vehicle state.
     * 
     * Note that we only update the prior mean, since
     * the covariance doesn't change in this filter
     * (because we're not updating priors, but instead 
     * sampling states). 
     * 
     */
    final Vector predictedMean =
        predictedMotionState.getMean().clone();
    final Vector noisyPredictedState =
        motionStatePredictor.addStateTransitionError(predictedMean,
            this.random);
    predictedMotionState.setMean(noisyPredictedState);
    this.sampledTransitionError =
        predictedMotionState.getMean().minus(predictedMean);
    final PathEdge startEdge =
        updatedState.getPathStateParam().getValue().getEdge();

    /*
     * We don't handle backward movement in this updater.
     */
    Preconditions.checkState(predictedMotionState
        .getInputDimensionality() == 4
        || predictedMotionState.getMean().getElement(0) >= 0d);

    final OnOffEdgeTransDistribution edgeTransDistribution =
        updatedState.getEdgeTransitionParam()
            .getConditionalDistribution();

    /*
     * Need this so that the sampler can tell which possible 
     * on-road edges exist at the new projected location.
     */
    if (startEdge.isNullEdge()) {
      edgeTransDistribution.setMotionState(predictedMotionState
          .getMean());
    }

    /*
     * We just use this edge as an indicator of whether we
     * go on or off road initially.  Once on or off is decided
     * we don't consider the change again.
     */
    final InferenceGraphSegment initialEdge =
        edgeTransDistribution.sample(this.random);

    final Path newPath;
    if (initialEdge.isNullEdge()) {
      if (!startEdge.isNullEdge()) {
        /*
         * Going off-road from on-road
         * We have to re-project after converting to off-road.
         */
        final MultivariateGaussian groundStateDist =
            new TruncatedRoadGaussian(previousState
                .getPathStateParam().getValue().getGroundState(),
                new SvdMatrix(MatrixFactory.getDefault()
                    .createMatrix(4, 4)));
        predictedMotionState =
            motionStatePredictor
                .createPredictiveDistribution(groundStateDist);
        final Vector offRoadPredictedMean =
            predictedMotionState.getMean().clone();
        final Vector offRoadNoisyPredictedState =
            motionStatePredictor.addStateTransitionError(
                offRoadPredictedMean, this.random);
        predictedMotionState.setMean(offRoadNoisyPredictedState);
        this.sampledTransitionError =
            offRoadNoisyPredictedState.minus(offRoadPredictedMean);
      }
      newPath = Path.nullPath;
    } else {
      if (startEdge.isNullEdge()) {
        /*
         * Going on-road from off-road
         */
        PathUtils.convertToRoadBelief(predictedMotionState,
            initialEdge, true, previousState.getPathStateParam()
                .getValue().getMotionState(),
            this.parameters.getInitialObsFreq());
        final InferenceGraphSegment segment =
            Iterables.getLast(initialEdge
                .getSegments(predictedMotionState.getMean()
                    .getElement(0)));
        newPath = new Path(new PathEdge(segment, 0d, false));

      } else {

        final double projectedDistance =
            predictedMotionState.getMean().getElement(0);
        final List<GraphPath> paths =
            this.getPathsUpToLength(new GraphPath(initialEdge),
                projectedDistance);

        if (paths.isEmpty()) {
          /*
           * We couldn't find a path of the desired distance, so
           * we're going off-road, I guess.
           */
          PathUtils.convertToGroundBelief(predictedMotionState,
              startEdge, true, false, true);
          newPath = Path.nullPath;
        } else {
          final GraphPath sampledGraphPath =
              paths.size() > 1 ? paths.get(this.random.nextInt(paths
                  .size() - 1)) : Iterables.getOnlyElement(paths);

          final List<PathEdge> pathEdges = Lists.newArrayList();
          double distance = 0d;
          for (final InferenceGraphSegment segment : sampledGraphPath
            .getEdges()) {
            pathEdges.add(new PathEdge(segment, distance, false));
            distance += segment.getLine().getLength();
          }
          newPath = new Path(pathEdges, false);
        }
      }
    }

    final PathState newPathState =
        new PathState(newPath, predictedMotionState.getMean());

    final MultivariateGaussian obsDist =
        motionStatePredictor.getObservationDistribution(
            predictedMotionState, newPathState.getEdge());

    updatedState.setMotionStateParam(
        SimpleBayesianParameter.create(obsDist.getMean(),
            obsDist, 
            /*
             * Important: we need the motion state prior to be relative to the edge it's
             * on, otherwise, distance along path will add up indefinitely. 
             */
            (MultivariateGaussian)new TruncatedRoadGaussian(newPathState.getEdgeState(),
              newPathState.isOnRoad() ? motionStatePredictor
                  .getRoadFilter().getModelCovariance()
                  : motionStatePredictor.getGroundFilter()
                      .getModelCovariance())
            ));

    updatedState.setPathStateParam(
        SimpleBayesianParameter.<PathState, PathStateMixtureDensityModel, PathStateDistribution>
          create(newPathState, null, 
            new PathStateDistribution(newPathState.getPath(), 
                predictedMotionState)));

    updatedState.setParentState(previousState);

    return updatedState;
  }

}