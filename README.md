# pathfinder

<img src="resources/public/images/boyscout.png" alt="Pathfinder Mascot" width="250" align="right" style="margin-left: 20px; margin-bottom: 10px;">

A career path discovery engine.

Pathfinder is a proof of concept for an application that lets the user
discover possible career paths, being that employment or further skill
development.  It tries to achieve such goal leveraging semantic
similarities between profile and job offers, between job offers and
online courses, market data and logistic constraints.

The app, although self-contained, is conceptually split between a
backend written in Clojure and a frontend written in ClojureScript.

The ultimate scope of this project is to showcase the use of text
embedding applied to a modern problem.

A notebook (Clay) and a couple of slides are included in the
`notebooks` directory.  You can also find a pre-formatted, static
version in the GitHub Pages:

   - [The Notebook](https://fourteatoo.github.io/pathfinder)
   - [The Slides](https://fourteatoo.github.io/pathfinder/slides-revealjs.html)


## Dependencies

Pathfinder needs a DuckDB installation, a local LLM or a Groq account.

You also need some data to populate your DB.


## Installation

Download from https://github.com/fourteatoo/pathfinder

To compile the jar:

   $ lein uberjar

To compile the documentation:

   $ lein build-docs
   
alternatively, to build the notebook:

   $ lein notebook
   
or the slides

   $ lein slides


At this point you should be hunting for your data, to see the app
working.  See the notebook for some ideas.


## Usage

To start the app is as simple as:

    $ java -jar pathfinder-0.1.0-standalone.jar [args]

then connect with your browser to http://localhost:8080 and you should
be good to go.


## Options

None.


### Bugs

This is a proof of concept, not an application you should try at home.

No data is provided, although the notebook contains tips on where to
find it.


## License

Copyright © 2026 Walter C. Pelissero

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
