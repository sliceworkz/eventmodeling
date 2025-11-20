[![ci build - mvn package](https://github.com/sliceworkz/eventmodeling/actions/workflows/ci.yaml/badge.svg)](https://github.com/sliceworkz/eventmodeling/actions/workflows/ci.yaml)
[![Status: Experimental](https://img.shields.io/badge/status-experimental-red)](#)
[![Docs](https://img.shields.io/badge/Event%20Modeling%20Quickstart%20Guide-0b5fff)](https://sliceworkz.github.io/posts/eventmodeling-quickstart/)

# About Event Modeling library

An opinionated implementation of Event Modeling patterns in Java.
Feature sliced software, Event Sources and modularized in feature slices.

The goal is to have a very lightweight implementation of all core techniques.

Supports the 4 EM core templates:
- State change	(Trigger -> Command -> Event)
- State read	(Events -> ReadModel -> UI/API
- Automation	(Events -> TODOList -> Processor -> Command -> Event)
- Translation	(External Event -> Processor -> Command -> Event)

And some utility facilities:
- Dispatcher	(Outbound Event outbox pattern)


# Getting started

Step-by-step introduction with the [quickstart guide](https://sliceworkz.github.io/posts/eventmodeling-quickstart/)


# Other

## Contributing

Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.


## License

This project is licensed under the LGPL-3.0 License - see the [LICENSE](LICENSE) file for details.
External components on which this project depends are listed in the [NOTICE](NOTICE) file.

