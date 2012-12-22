package org.openplans.tools.tracking.impl.graph.paths;

import gov.sandia.cognition.math.LogMath;
import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.statistics.bayesian.BayesianCredibleInterval;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;
import gov.sandia.cognition.statistics.distribution.UnivariateGaussian;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.commons.lang.builder.CompareToBuilder;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.openplans.tools.tracking.impl.Observation;
import org.openplans.tools.tracking.impl.VehicleState;
import org.openplans.tools.tracking.impl.WrappedWeightedValue;
import org.openplans.tools.tracking.impl.graph.InferredEdge;
import org.openplans.tools.tracking.impl.statistics.DataCube;
import org.openplans.tools.tracking.impl.statistics.StatisticsUtil;
import org.openplans.tools.tracking.impl.statistics.filters.road_tracking.AbstractRoadTrackingFilter;
import org.openplans.tools.tracking.impl.statistics.filters.road_tracking.AbstractRoadTrackingFilter.PathEdgeProjection;
import org.openplans.tools.tracking.impl.util.OtpGraph;

import com.beust.jcommander.internal.Maps;
import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.linearref.LengthIndexedLine;

/**
 * Inferred paths are collections of PathEdges that track the distance traveled
 * and the direction (by sign)
 * 
 * @author bwillard
 * 
 */
public class InferredPath implements Comparable<InferredPath> {

  public static class EdgePredictiveResults {

    final PathStateBelief beliefPrediction;
    final PathStateBelief locationPrediction;
    final double edgePredMarginalLogLik;
    final double edgePredTransLogLik; 
    final double measurementPredLik;
    
    public EdgePredictiveResults(
      PathStateBelief beliefPrediction,
      PathStateBelief locationPrediction,
      double edgePredMarginalLogLik,
      double edgePredTransLogLik, double measurementPredLik) {
      this.beliefPrediction = locationPrediction;
      this.locationPrediction = locationPrediction;
      this.edgePredMarginalLogLik = edgePredMarginalLogLik;
      this.edgePredTransLogLik = edgePredTransLogLik;
      this.measurementPredLik = measurementPredLik;
    }

    public PathStateBelief getBeliefPrediction() {
      return beliefPrediction;
    }
    
    public PathStateBelief getLocationPrediction() {
      return locationPrediction;
    }

    public double getEdgePredMarginalLogLik() {
      return edgePredMarginalLogLik;
    }

    public double getEdgePredTransLogLik() {
      return edgePredTransLogLik;
    }

    public double getMeasurementPredLik() {
      return measurementPredLik;
    }

    public double getTotalLogLik() {
      return edgePredMarginalLogLik + edgePredTransLogLik
          + measurementPredLik;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder
          .append("EdgePredictiveResults")
          .append("[locationPrediction=")
          .append(locationPrediction)
          .append(", edgePredMarginalLogLik=")
          .append(edgePredMarginalLogLik)
          .append(", edgePredTransLogLik=")
          .append(edgePredTransLogLik)
          .append(", measurementPredLik=")
          .append(measurementPredLik).append("]");
      return builder.toString();
    }


  }

  private final ImmutableList<PathEdge> edges;
  private final Double totalPathDistance;

  public List<Integer> edgeIds = Lists.newArrayList();

  /*
   * These are the edges used in path finding.
   */
  private InferredEdge startSearchEdge;

  private InferredEdge endSearchEdge;

  /*
   * Note: single edges are considered forward
   */
  private Boolean isBackward = null;

  private final Geometry geometry;

  private static InferredPath emptyPath = new InferredPath();

  private InferredPath() {
    this.edges = ImmutableList.of(PathEdge.getEmptyPathEdge());
    this.totalPathDistance = null;
    this.isBackward = null;
    this.geometry = null;
  }

  private InferredPath(ImmutableList<PathEdge> edges,
    boolean isBackward) {
    Preconditions.checkArgument(edges.size() > 0);
    Preconditions.checkState(
        Iterables.getFirst(edges, null).getDistToStartOfEdge() == 0d);
    this.edges = edges;
    this.isBackward = isBackward;

    PathEdge lastEdge = null;
    //    double absTotalDistance = 0d;
    final List<Coordinate> coords = Lists.newArrayList();
    for (final PathEdge edge : edges) {

      if (!edge.isEmptyEdge()) {
        if (isBackward) {
          Preconditions.checkArgument(lastEdge == null || lastEdge.getInferredEdge()
              .getStartVertex()
              .equals(edge.getInferredEdge().getEndVertex()));
        } else {
          Preconditions.checkArgument(lastEdge == null || lastEdge.getInferredEdge()
              .getEndVertex()
              .equals(edge.getInferredEdge().getStartVertex()));

        }

        final Geometry geom = edge.getGeometry();
        if (geom.getLength() > 1e-4) {
          final Coordinate[] theseCoords =
              isBackward ? geom.reverse().getCoordinates() : geom
                  .getCoordinates();
          final int startIdx = coords.size() == 0 ? 0 : 1;
          for (int i = startIdx; i < theseCoords.length; i++) {
            if (i == 0
                || !theseCoords[i]
                    .equals(coords.get(coords.size() - 1)))
              coords.add(theseCoords[i]);
          }
          edgeIds.add(edge.getInferredEdge().getEdgeId());
        }
      }

      lastEdge = edge;
    }

    if (edges.size() > 1) {
      this.geometry =
          JTSFactoryFinder.getGeometryFactory().createLineString(
              coords.toArray(new Coordinate[coords.size()]));
    } else {
      final Geometry edgeGeom =
          Iterables.getOnlyElement(edges).getGeometry();
      this.geometry = isBackward ? edgeGeom.reverse() : edgeGeom;
    }

    final double direction = isBackward ? -1d : 1d;
    this.totalPathDistance = direction * this.geometry.getLength();
  }

  /*
   * Dangerous, since it loses direction info.
   */
  //  private InferredPath(InferredEdge inferredEdge) {
  //    Preconditions.checkArgument(!inferredEdge.isEmptyEdge());
  //    this.edges =
  //        ImmutableList.of(PathEdge.getEdge(inferredEdge, 0d, false));
  //    this.totalPathDistance = inferredEdge.getLength();
  //    this.isBackward = Boolean.FALSE;
  //    this.edgeIds.add(inferredEdge.getEdgeId());
  //    this.geometry = inferredEdge.getGeometry();
  //  }

  private InferredPath(PathEdge edge) {
    Preconditions.checkArgument(!edge.isEmptyEdge());
    Preconditions.checkArgument(edge.getDistToStartOfEdge() == 0d);
    this.edges = ImmutableList.of(edge);
    this.isBackward = edge.isBackward();
    this.totalPathDistance =
        (this.isBackward ? -1d : 1d)
            * edge.getInferredEdge().getLength();
    this.edgeIds.add(edge.getInferredEdge().getEdgeId());
    this.geometry = this.isBackward ? 
        edge.getGeometry().reverse() : 
          edge.getGeometry();
  }

  /**
   * TODO FIXME: make this a generic getStateOnPath (replace the other
   * one).
   * @see {@link InferredPath#getStateOnPath(AbstractPathState)}
   * @param stateBelief
   * @return
   */
  public PathStateBelief getStateBeliefOnPath(PathStateBelief stateBelief) {
    final MultivariateGaussian edgeStateBelief;
    /*
     * Make sure it starts on this path.
     */
    if (!this.isEmptyPath()) {
      edgeStateBelief = stateBelief.getGlobalStateBelief().clone();
      /*
       * Note that we force projection onto the first edge
       * in this path.
       */
      if (!stateBelief.isOnRoad()) {
        AbstractRoadTrackingFilter.convertToRoadBelief(
            edgeStateBelief, this,
            Iterables.getFirst(this.getEdges(), null), true);
      } else {
        final Vector convertedState =
            this.getStateOnPath(stateBelief);
        edgeStateBelief.setMean(convertedState);
      }
    } else {
      edgeStateBelief = stateBelief.getGroundBelief();
    }
    
    return PathStateBelief.getPathStateBelief(this, edgeStateBelief);
  }
  
  

  public double clampToPath(final double distance) {
    final double dir = this.getIsBackward() ? -1d : 1d;
    final LengthIndexedLine lil =
        new LengthIndexedLine(this.getGeometry());
    final double clampedIndex = dir * lil.clampIndex(dir * distance);
    return clampedIndex;
  }
  

  @Override
  public int compareTo(InferredPath o) {
    final CompareToBuilder comparator = new CompareToBuilder();
    comparator.append(this.edges.toArray(), o.edges.toArray());
    return comparator.toComparison();
  }
  

  /**
   * Converts location component of the mean to a location on this path, if any. <br>
   * Basically, if the state isn't already defined on the path, then we check if
   * the state's edge is the opposite direction to the first edge on this path.
   * If so, we can convert the state to this path's direction.
   * 
   * @param beliefPrediction
   */
  public Vector getStateOnPath(AbstractPathState currentState) {

    PathState startState = PathState.getPathState(
        this, VectorFactory.getDefault().createVector2D(
            0d, 0d));
    
    Vector diff = startState.minus(currentState);
    diff.negativeEquals();
    
    Vector normState = VectorFactory.getDefault().createVector2D(
        this.clampToPath(diff.getElement(0)),
        diff.getElement(1));
    
    /*
     * Make sure that we're still on the same edge.
     */
    boolean foundMatch = false;
    for (PathEdge thisEdge : this.edges) {
      if (thisEdge.isOnEdge(normState.getElement(0))
          && currentState.getEdge().getGeometry()
            .equalsTopo(thisEdge.getGeometry())) {
        foundMatch = true;
        break;
      }
    }
    
    if (!foundMatch)
      return null;
    
//    PathState result = PathState.getPathState(
//        this, diff.negative());
    
//    final InferredEdge presentEdge = currentState.getEdge().getInferredEdge();
//    final InferredEdge startEdge = edges.get(0).getInferredEdge();
//    
//    Preconditions.checkState(presentEdge.getGeometry()
//        .equalsTopo(startEdge.getGeometry()));
//    
//    final Vector normState = currentState.getLocalState().clone();
//    
//
//    final double currentLocation = normState.getElement(0);
//    final double destDirection = this.isBackward ? -1d : 1d;
//    final double srcDirection = currentState.getPath().isBackward ? -1d : 1d;
//
//    final boolean pathEdgeIsReverse =
//        !presentEdge.getGeometry().equalsExact(
//            startEdge.getGeometry());
//    if (pathEdgeIsReverse) {
//      if (destDirection * srcDirection == 1d) {
//        normState.setElement(0,
//            destDirection
//                * (startEdge.getLength() - Math.abs(currentLocation)));
//      } else {
//        normState.setElement(0, -1d * currentLocation);
//      }
//      normState.setElement(1, -1d * normState.getElement(1));
//    } else {
//      if (destDirection * srcDirection == -1d) {
//        normState.setElement(0, destDirection * startEdge.getLength()
//            + currentLocation);
//      }
//    }

    assert (this.isOnPath(normState.getElement(0)));
    assert Preconditions.checkNotNull( 
     PathState.getPathState(this, 
         normState).minus(currentState)
       .isZero(AbstractRoadTrackingFilter
           .getEdgeLengthErrorTolerance()) ? Boolean.TRUE : null);

    return normState;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final InferredPath other = (InferredPath) obj;
    if (edges == null) {
      if (other.edges != null) {
        return false;
      }
    } else if (!edges.equals(other.edges)) {
      return false;
    }
    return true;
  }

  /**
   * Does the same as {@link #isOnPath(double)} except it returns the trimmed
   * location if it falls within allowable error, and works with/returns a state
   * vector.
   * 
   * @param distance
   * @return
   */
  public PathState getCheckedStateOnPath(Vector state,
    double tolerance) {
    Preconditions.checkState(!isEmptyPath());
    Preconditions.checkArgument(tolerance >= 0d);
    Preconditions.checkArgument(state.getDimensionality() == 2);

    final Vector newState = state.clone();
    final double distance = newState.getElement(0);
    final double direction = Math.signum(totalPathDistance);
    final double overTheEndDist =
        direction * distance - Math.abs(totalPathDistance);
    if (overTheEndDist > 0d) {
      if (overTheEndDist > tolerance) {
        return null;
      } else {
        newState.setElement(0, totalPathDistance);
      }
    } else if (direction * distance < 0d) {
      if (direction * distance < tolerance) {
        return null;
      } else {
        newState.setElement(0, direction * 0d);
      }
    }

    return PathState.getPathState(this, newState);
  }

  /**
   * Returns the farthest PathEdge that the given distance
   * could correspond to.
   * The clamp option will clamp the distance to the
   * beginning or end of the path.
   * 
   * @param distance
   * @param clamp
   * @return
   */
  public PathEdge getEdgeForDistance(double distance, boolean clamp) {
    final double direction = Math.signum(totalPathDistance);
    if (direction * distance - Math.abs(totalPathDistance) 
        > AbstractRoadTrackingFilter.getEdgeLengthErrorTolerance()) {
      return clamp ? Iterables.getLast(edges) : null;
    } else if (direction * distance < 0d) {
      return clamp ? Iterables.getFirst(edges, null) : null;
    }

    for (final PathEdge edge : edges.reverse()) {
      if (edge.isOnEdge(distance))
        return edge;
    }

    assert Preconditions.checkNotNull(null);

    return null;
  }

  public List<Integer> getEdgeIds() {
    return edgeIds;
  }

  public ImmutableList<PathEdge> getEdges() {
    return edges;
  }

  public InferredEdge getEndSearchEdge() {
    return endSearchEdge;
  }

  public Geometry getGeometry() {
    return geometry;
  }

  public Boolean getIsBackward() {
    return isBackward;
  }

  /**
   * XXX: the state must have a prior predictive mean.
   * 
   * @param obs
   * @param state
   * @param edgeToPreBeliefAndLogLik
   * @return
   */
  public InferredPathEntry getPredictiveLogLikelihood(
    Observation obs, VehicleState state,
    Map<PathEdge, EdgePredictiveResults> edgeToPreBeliefAndLogLik) {

    /*
     * We allow transitions from off-road onto a path, and vice-versa.  
     * Otherwise, we require that the first edge of the path is the edge of the
     * current state.
     */
    if (!(!state.getBelief().isOnRoad() || this.isEmptyPath() || state
        .getBelief().getEdge().getInferredEdge()
        .getGeometry()
        .equals(
            Iterables.getFirst(this.getEdges(), null)
                .getInferredEdge().getGeometry())))
      return null;

    /*
     * Lazily load this so we don't repeat work for dups.
     */
    PathStateBelief beliefPrediction = null;
    final AbstractRoadTrackingFilter<?> filter =
        state.getMovementFilter();

    double pathLogLik = Double.NEGATIVE_INFINITY;
    double edgePredMarginalTotalLik = Double.NEGATIVE_INFINITY;

    final List<WrappedWeightedValue<PathEdge>> weightedPathEdges =
        Lists.newArrayList();
    for (final PathEdge edge : this.getEdges()) {

      final EdgePredictiveResults results; 

      if (edgeToPreBeliefAndLogLik.containsKey(edge)) {
        final EdgePredictiveResults edgeResults =
            edgeToPreBeliefAndLogLik.get(edge);
        
        if (edgeResults == null)
          continue;

        PathStateBelief locationPrediction = 
            edgeResults.getLocationPrediction();
        
        if (locationPrediction == null)
          continue;
        
        /*
         * Although path edges may be the same, transitions
         * aren't guaranteed to be, especially for starting
         * edges (off-on, on-on would mistakenly be equal).
         */
        final double edgeTransLik = 
            state.getEdgeTransitionDist().logEvaluate(
              state.getBelief().getEdge().getInferredEdge(),
              locationPrediction.getEdge().getInferredEdge());
        
        results = new EdgePredictiveResults(
            beliefPrediction,
            locationPrediction,
            edgeResults.getEdgePredMarginalLogLik(),
            edgeTransLik,
            edgeResults.getMeasurementPredLik());
      } else {
        
        if (beliefPrediction == null) {
          beliefPrediction = filter.predict(state.getBelief(), this);
        }
        
        results = computePredictiveLogLikelihoodForEdge(
            edge, state, beliefPrediction, obs);
        
        edgeToPreBeliefAndLogLik.put(edge, results);
      }
      
      final double totalLogLik = results.getTotalLogLik();
      
      if (results.getLocationPrediction() == null)
        continue;
        
      weightedPathEdges.add(new WrappedWeightedValue<PathEdge>(edge,
          totalLogLik));
      pathLogLik = LogMath.add(pathLogLik, totalLogLik);
      
      edgePredMarginalTotalLik =
          LogMath.add(edgePredMarginalTotalLik,
              results.getEdgePredMarginalLogLik());
      
    
    }

    /*
     * Total of zero likelihood
     */
    if (Double.isInfinite(pathLogLik)
        && Double.isInfinite(edgePredMarginalTotalLik))
      return null;
    
    /*
     * Normalize with respect to the edges' predictive marginals 
     */
    if (!this.isEmptyPath())
      pathLogLik = pathLogLik - edgePredMarginalTotalLik;

    assert !Double.isNaN(pathLogLik);

    return new InferredPathEntry(this, edgeToPreBeliefAndLogLik,
        filter, weightedPathEdges, pathLogLik);
  }

  private EdgePredictiveResults computePredictiveLogLikelihoodForEdge(PathEdge edge,
    VehicleState state, PathStateBelief beliefPrediction, Observation obs) {
    
    final PathStateBelief locationPrediction;
    
    /*
     * Edge marginal predictive likelihoods.
     * Note: doesn't apply to free-movement, defaults to 0.
     */
    final double edgePredMarginalLogLik;
    if (edge.isEmptyEdge()) {
      edgePredMarginalLogLik = 0d;
      locationPrediction = beliefPrediction;
    } else {
      /*
       * Since we use the raw state's (no truncation) when producing
       * predicted locations, we also check that the predicted location
       * is on the edge.  If it isn't we drop this possibility. 
       */
      final MultivariateGaussian edgePrediction = predict(
          beliefPrediction, obs, edge);
      
      if (edgePrediction != null
          && edge.isOnEdge(edgePrediction.getMean().getElement(0))) {
        
        edgePredMarginalLogLik =
            marginalPredictiveLogLikelihood(edge,
                beliefPrediction.getRawStateBelief());
        locationPrediction = PathStateBelief.getPathStateBelief(this, edgePrediction);
      } else {
        locationPrediction = null;
        edgePredMarginalLogLik = Double.NaN;
      }
    }
    
    final double measurementPredLik;
    final double edgePredTransLogLik;
    if (locationPrediction != null) {

      measurementPredLik =
          state.getMovementFilter().priorPredictiveLogLikelihood(
              obs.getProjectedPoint(), locationPrediction);
      
      edgePredTransLogLik =
          state.getEdgeTransitionDist().logEvaluate(
              state.getBelief().getEdge().getInferredEdge(),
              locationPrediction.getEdge().getInferredEdge());

    } else {
      edgePredTransLogLik = Double.NaN;
      measurementPredLik = Double.NaN;
    }

    return new EdgePredictiveResults(
        beliefPrediction,
        locationPrediction, 
        edgePredMarginalLogLik,
        edgePredTransLogLik,
        measurementPredLik);
  }
  
  public InferredEdge getStartSearchEdge() {
    return startSearchEdge;
  }

  public Double getTotalPathDistance() {
    return totalPathDistance;
  }

  @Beta
  private double getTruncatedMean(final double origMean,
    double stdDev, PathEdge edge) {
    final double direction = edge.isBackward() ? -1d : 1d;
    final double mean =
        (Math.signum(origMean) * direction) * Math.abs(origMean);
    final double startDistance =
        Math.abs(edge.getDistToStartOfEdge());
    final double endDistance =
        edge.getLength() + startDistance;

    final double tt1 =
        UnivariateGaussian.PDF.logEvaluate(startDistance, mean,
            stdDev * stdDev);
    final double tt2 =
        UnivariateGaussian.PDF.logEvaluate(endDistance, mean, stdDev
            * stdDev);
    final double t1 =
        LogMath
            .subtract(tt1 > tt2 ? tt1 : tt2, tt1 > tt2 ? tt2 : tt1);

    final double t2 =
        LogMath
            .subtract(StatisticsUtil.normalCdf(endDistance, mean,
                stdDev, true), StatisticsUtil.normalCdf(
                startDistance, mean, stdDev, true));

    final double d2 = Math.log(stdDev) + t1 - t2;
    final double tmean = mean + (tt2 > tt1 ? -1d : 1d) * Math.exp(d2);

    return direction * tmean;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result =
        prime * result + ((edges == null) ? 0 : edges.hashCode());
    return result;
  }

  public boolean isEmptyPath() {
    return this == emptyPath;
  }

  /**
   * Checks if the distance is on the path given some allowable error defined by
   * {@link AbstractRoadTrackingFilter#getEdgeLengthErrorTolerance()}.
   * 
   * @param distance
   * @return
   */
  public boolean isOnPath(double distance) {

    Preconditions.checkState(!isEmptyPath());

    final double direction = Math.signum(totalPathDistance);
    final double overTheEndDist =
        direction * distance - Math.abs(totalPathDistance);
    if (overTheEndDist > AbstractRoadTrackingFilter
        .getEdgeLengthErrorTolerance()) {
      return false;
    } else if (direction * distance < -AbstractRoadTrackingFilter
        .getEdgeLengthErrorTolerance()) {
      return false;
    }

    return true;
  }

  public double marginalPredictiveLogLikelihood(PathEdge edge,
    MultivariateGaussian beliefPrediction) {
    //    MultivariateGaussian beliefPrediction, double pathEdgeDistNormFactor) {
    Preconditions.checkArgument(beliefPrediction
        .getInputDimensionality() == 2);
    final Matrix Or = AbstractRoadTrackingFilter.getOr();
    final double var =
        Or.times(beliefPrediction.getCovariance())
            .times(Or.transpose()).getElement(0, 0)
            + Math.pow(edge.getInferredEdge().getLength(), 2d) / 12d;
    final double mean =
        Or.times(beliefPrediction.getMean()).getElement(0);
    final double direction = Math.signum(totalPathDistance);

    final double evalPoint =
        (edge.getDistToStartOfEdge() + (edge.getDistToStartOfEdge() + direction
            * edge.getInferredEdge().getLength())) / 2d;

    final double result =
        UnivariateGaussian.PDF.logEvaluate(evalPoint, mean, var);
    //        + pathEdgeDistNormFactor; 

    return result;
  }

  public double marginalPredictiveLogLikelihoodOld(PathEdge edge,
    MultivariateGaussian beliefPrediction) {
    Preconditions.checkArgument(beliefPrediction
        .getInputDimensionality() == 2);
    final Matrix Or = AbstractRoadTrackingFilter.getOr();
    final double stdDev =
        Math.sqrt(Or.times(beliefPrediction.getCovariance())
            .times(Or.transpose()).getElement(0, 0));
    final double mean =
        Or.times(beliefPrediction.getMean()).getElement(0);
    final double direction = Math.signum(totalPathDistance);
    final double distToEndOfEdge =
        direction * edge.getInferredEdge().getLength()
            + edge.getDistToStartOfEdge();
    final double startDistance =
        direction > 0d ? edge.getDistToStartOfEdge()
            : distToEndOfEdge;
    final double endDistance =
        direction > 0d ? distToEndOfEdge : edge
            .getDistToStartOfEdge();

    // FIXME use actual log calculations
    final double result =
        LogMath
            .subtract(StatisticsUtil.normalCdf(endDistance, mean,
                stdDev, true), StatisticsUtil.normalCdf(
                startDistance, mean, stdDev, true));

    return result;
  }

  /**
   * Produce an edge-conditional prior predictive distribution.
   * XXX: Requires that belief be in terms of this path. 
   * 
   * @param belief
   * @param edge2
   */
  public MultivariateGaussian predict(PathStateBelief belief,
    Observation obs, PathEdge edge) {

    Preconditions.checkArgument(belief.isOnRoad());
    Preconditions.checkArgument(edges.contains(edge));
    Preconditions.checkArgument(belief.getPath().equals(this));

    /*-
     * TODO really, this should just be the truncated/conditional
     * mean and covariance for the given interval/edge
     */
    final Matrix Or = AbstractRoadTrackingFilter.getOr();
    final double S =
        Or.times(belief.getCovariance()).times(Or.transpose())
            .getElement(0, 0)
            // + 1d;
            + Math
                .pow(
                    edge.getInferredEdge().getLength()
                        / Math.sqrt(12), 2);
    final Matrix W =
        belief.getCovariance().times(Or.transpose()).scale(1 / S);
    final Matrix R =
        belief.getCovariance().minus(W.times(W.transpose()).scale(S));

    final double direction = Math.signum(totalPathDistance);
    final double mean =
        (edge.getDistToStartOfEdge() + (edge.getDistToStartOfEdge() + direction
            * edge.getInferredEdge().getLength())) / 2d;

    final Vector beliefMean = belief.getRawState();
    final double e = mean - Or.times(beliefMean).getElement(0);
    final Vector a = beliefMean.plus(W.getColumn(0).scale(e));

    final Vector aAdj =
        edge.getCheckedStateOnEdge(a,
            AbstractRoadTrackingFilter.getEdgeLengthErrorTolerance(),
            false);

    if (aAdj == null) {
      return null;
    }

    assert StatisticsUtil
        .isPosSemiDefinite((gov.sandia.cognition.math.matrix.mtj.DenseMatrix) R);

    final MultivariateGaussian prediction =
        new MultivariateGaussian(aAdj, R);
    return prediction;
  }

  public void setEndSearchEdge(InferredEdge endEdge) {
    this.endSearchEdge = endEdge;
  }

  public void setIsBackward(Boolean isBackward) {
    this.isBackward = isBackward;
  }

  public void setStartSearchEdge(InferredEdge startEdge) {
    this.startSearchEdge = startEdge;
  }

  @Override
  public String toString() {
    if (this == emptyPath)
      return "InferredPath [empty path]";
    else
      return "InferredPath [edges=" + edgeIds
          + ", totalPathDistance=" + totalPathDistance + "]";
  }

  public void updateEdges(Observation obs,
    MultivariateGaussian stateBelief, OtpGraph graph) {

    if (this.isEmptyPath())
      return;

    final BayesianCredibleInterval ciInterval =
        BayesianCredibleInterval.compute(new UnivariateGaussian(
            stateBelief.getMean().getElement(1), stateBelief
                .getCovariance().getElement(1, 1)), 0.95);

    /*
     * If we could be stopped, then don't update this
     */
    if (ciInterval.withinInterval(0d))
      return;

    final double velocity = stateBelief.getMean().getElement(1);
    for (final PathEdge edge : this.getEdges()) {
      if (!edge.isEmptyEdge()) {
        edge.getInferredEdge()
            .getVelocityEstimator()
            .update(
                edge.getInferredEdge().getVelocityPrecisionDist(),
                Math.abs(velocity));

        final HashMap<String, Integer> attributes =
            new HashMap<String, Integer>();

        final Integer interval =
            Math.round(((obs.getTimestamp().getHours() * 60) + obs
                .getTimestamp().getMinutes()) / DataCube.INTERVAL);

        attributes.put("interval", interval);
        attributes.put("edge", edge.getInferredEdge().getEdgeId());

        graph.getDataCube().store(Math.abs(velocity), attributes);
      }
    }
  }

  /*
   * Again, dangerous because it loses path info.
   */
  //  public static InferredPath
  //      getInferredPath(InferredEdge inferredEdge) {
  //    if (inferredEdge.isEmptyEdge())
  //      return emptyPath;
  //    else
  //      return new InferredPath(inferredEdge);
  //  }

  public static InferredPath getEmptyPath() {
    return emptyPath;
  }

  public static InferredPath getInferredPath(
    Collection<PathEdge> edges, boolean isBackward) {
    if (edges.size() == 1) {
      final PathEdge edge = Iterables.getOnlyElement(edges);
      if (edge.isEmptyEdge())
        return emptyPath;
    }
    return new InferredPath(ImmutableList.copyOf(edges), isBackward);
  }

  public static InferredPath getInferredPath(PathEdge pathEdge) {
    if (pathEdge.isEmptyEdge())
      return emptyPath;
    else
      return new InferredPath(pathEdge);
  }
}
