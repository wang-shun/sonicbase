#!/bin/bash

export _XMX_=1g
export SEARCH_HOME="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"/..
export LOG4J_FILE="cli-log4j.xml"
./runclass com.lowryengineering.database.cli.Cli2 "$@"
