This library contains a set of tools for tracking objects through GPS data
real-time.  Specifically, it contains implementations of Bayesian particle
filters for on-road and off-road 2D motion and state-parameter estimation.

The generic api uses a graph and some observations to produce sequential
filtered results, which infer the true location, velocity, their variances, the
street it's on/the path it took.  Extensions in this library include the
ability to estimate parameters, like GPS and acceleration errors.  See
https://github.com/openplans/open-tracking-tools/wiki for a better description.

To get started, you can build an OpenTripPlanner graph by specifying coordinate
boundaries for OpenStreetMap data, then running the TraceRunner in
open-tracking-tools-otp over a CSV file.  (See
https://github.com/openplans/OpenTripPlanner/wiki/GraphBuilder for more
information about OTP graph building.)  An example xml build file for an OTP
graph can be found in the open-tracking-tools-otp project, as well as an
example TraceRunner config file.

Another example of a graph is GenericJTSGraph.java, which takes simple JTS
geometry objects.

There is also a simulator that will produce observations for testing.  See
RoadTrackingGraphFilterTest.java for a complete example of graph construction,
simulation and filtering. 

## Building a Graph in Ecplise

1. [Download](http://www.eclipse.org/downloads/) and setup Eclipse as usual.

2. [Download](http://projectlombok.org/) and install the Lombok plugin for
Eclipse.

3. Clone this repository

```bash 
$ git clone https://github.com/open-city/open-tracking-tools.git
```

4. Clone the Open City fork of the OpenTripPlanner repo

``` bash
$ git clone https://github.com/open-city/OpenTripPlanner.git
```

6. Import both the open-tracking-tools project and the OpenTripPlanner project 
into Eclipse as "Existing Maven Projects".

7. Under opentripplanner-graph-builder > target, right-click the
graph-builder.jar file and create a Run configuration.

8. Create a new "Java Application" configuration.

9. On the "Main" tab, the "Main class" should be
``org.opentripplanner.graph_builder.GraphBuilderMain``

10. On the "Arguments" tab, put the absolute path to [this
file](https://github.com/open-city/open-tracking-tools/blob/master/open-tracking-tools-otp/clearstreets-graph-builder.xml)
in the "Program Arguments" box and under "VM Arguments" put "-Xmx2048M" (the
graph builder needs at least that much memory).

11. On the "Classpath" tab, click "User entries" and then the "Add projects"
button on the right. Add the ``open-tracking-tools-api`` and
``open-tracking-tools-otp`` projects. You'll also need to add the ``jar`` file
for the Cognitive Foundary components (for some reason this does not get
resolve on it's own). To do this, you'll click the "Add External JARs" button
on the right and navigate to where Maven cached it. This is typically in your
home directory in a folder called ``.m2``. The path will look something like: ``
~/.m2/repository/gov/sandia/foundry/cognitive-foundry/3.3.2``.

12. Before you run the program, make sure that you have a ``/tmp/osm-cache``
folder where the downloaded OSM files can be cached. You can edit this
in the configuration
[here](https://github.com/open-city/open-tracking-tools/blob/master/open-tracking-tools-otp/clearstreets-graph-builder.xml#L19)
The "Run" button in the lower right hand corner of that dialog box should be
clickable now so click it. The graph builder will start downloading all of the
OSM data that it needs (which will take a while).
