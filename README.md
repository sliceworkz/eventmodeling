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

## Guides

- [Choosing a read model](CHOOSING-A-READ-MODEL.md) — which of the read model kinds to reach for, in
  the order to reach for them, and what each step costs. Start here before optimising a read: the
  cheapest fix is usually a step you have not taken yet rather than the one below it.
  [READ-MODEL-MANUAL.md](READ-MODEL-MANUAL.md) walks the same ladder as a tutorial, one chapter per
  step.
- [Where a validation goes](WHERE-VALIDATIONS-GO.md) — the places a validation can run (value
  objects, command input checks, decision models and DCB, uniqueness, the edges), what each can
  guard, and the one rule that sorts them: a validation runs before events exist, or never.
- [Who may do what](WHO-MAY-DO-WHAT.md) — a built bounded context can execute commands, erase a
  person's data, stop an automation and terminate itself, and almost no caller needs all of that.
  The audiences each capability is filed under, and how a controller, an inbound adapter or an
  admin endpoint holds only its own — at the cost of a reference type.


# Other

## Contributing

Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.


## License

This project is licensed under the LGPL-3.0 License - see the [LICENSE](LICENSE) file for details.
External components on which this project depends are listed in the [NOTICE](NOTICE) file.

