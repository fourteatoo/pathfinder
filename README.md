# pathfinder

A career path discovery engine.

Pathfinder is a PoC for an application that lets the user discover
possible career paths, being that employment or further skill
development.  It tries to achieve that leveraging semantic
similarities between profile and job offers, between job offers and
online courses, market data and logistic constraints.

The app, although monolithic, is conceptually split between a backend
written in Clojure and a frontend written in ClojureScript.

A notebook (Clay) and a couple of slides are included in the
`notebooks` directory.


## Dependencies

Pathfinder needs a DuckDB installation, a local LLM or a Groq account.

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


## Usage

To start the app is as simple as:

    $ java -jar pathfinder-0.1.0-standalone.jar [args]

then connect with your browser to http://localhost:8080 and you should
be good to go.

## Options

TODO

### Bugs

Aplenty.  This is a proof of concept and it is not "production" ready.


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
