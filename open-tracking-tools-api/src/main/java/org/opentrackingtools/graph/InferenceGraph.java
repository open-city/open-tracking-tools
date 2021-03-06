package org.opentrackingtools.graph;

import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.statistics.DistributionWithMean;

import java.util.Collection;

import org.opentrackingtools.model.GpsObservation;
import org.opentrackingtools.model.VehicleStateDistribution;
import org.opentrackingtools.paths.Path;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;

public interface InferenceGraph {

  public boolean edgeHasReverse(Geometry edge);

  public Envelope getGPSGraphExtent();

  public Collection<InferenceGraphEdge> getIncomingTransferableEdges(
    InferenceGraphEdge infEdge);

  public InferenceGraphEdge getInferenceGraphEdge(String id);

  public Collection<InferenceGraphSegment> getNearbyEdges(
    Coordinate projLocation, double radius);

  public Collection<InferenceGraphSegment> getNearbyEdges(
    DistributionWithMean<Vector> tmpInitialBelief, Matrix covariance);

  public Collection<InferenceGraphSegment> getNearbyEdges(
    Vector projLocation, double radius);

  public Collection<InferenceGraphEdge> getOutgoingTransferableEdges(
    InferenceGraphEdge infEdge);

  public Collection<Path> getPaths(
    VehicleStateDistribution<? extends GpsObservation> fromState,
    GpsObservation toCoord);

  public Envelope getProjGraphExtent();

  public Collection<InferenceGraphEdge> getTopoEquivEdges(
    InferenceGraphEdge edge);

}