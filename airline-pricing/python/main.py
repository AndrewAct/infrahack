import dataclasses
from abc import ABC, abstractmethod
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from enum import Enum
from multiprocessing import Value
from typing import Iterable, Iterator

CENT = Decimal("0.01")


class SeatClass(str, Enum):
    ECONOMY = "Economy"
    PREMIUM = "Premium"
    BUSINESS = "Business"


@dataclasses.dataclass
class TicketRequest:
    airline: str
    miles: Decimal
    seat_class: SeatClass

    def __post_init__(self) -> None:
        if not self.airline or not self.miles.is_finite() or self.miles < 0:
            raise ValueError("airline is required and miles must be non-negative")

    @classmethod
    def parse(cls, line: str) -> TicketRequest:
        parts = line.split(" ")
        if len(parts) != 3:
            raise ValueError(f"invalid ticket request: {line}")
        airline, miles_str, seat_class_str = parts
        try:
            return cls(airline, Decimal(miles_str), SeatClass(seat_class_str))
        except (InvalidOperation, ValueError) as error:
            raise ValueError(f"invalid ticket request: {line}") from error


class OperatingCostCalculator:
    def calculate(self, ticket_request: TicketRequest) -> Decimal:
        if ticket_request.seat_class is SeatClass.ECONOMY:
            return Decimal(0)
        if ticket_request.seat_class is SeatClass.PREMIUM:
            return Decimal(25)
        if ticket_request.seat_class is SeatClass.BUSINESS:
            return Decimal(50) + Decimal(0.25) * ticket_request.miles
        raise AssertionError(f"unhandled seat class: {ticket_request.seat_class}")


class PricingStrategy(ABC):
    @abstractmethod
    def calculate(
        self, ticket_request: TicketRequest, operating_cost: Decimal
    ) -> Decimal:
        raise NotImplementedError


class DeltaPricingStrategy(PricingStrategy):
    def calculate(
        self, ticket_request: TicketRequest, operating_cost: Decimal
    ) -> Decimal:
        return Decimal("0.50") * ticket_request.miles + operating_cost


class UnitedPricingStrategy(PricingStrategy):
    def calculate(
        self, ticket_request: TicketRequest, operating_cost: Decimal
    ) -> Decimal:
        price = Decimal("0.75") * ticket_request.miles + operating_cost
        if ticket_request.seat_class is SeatClass.PREMIUM:
            price += Decimal("0.10") * ticket_request.miles
        return price


class SouthwestPricingStrategy(PricingStrategy):
    def calculate(
        self, ticket_request: TicketRequest, operating_cost: Decimal
    ) -> Decimal:
        del operating_cost
        return ticket_request.miles


class StrategyFactory:
    def __init__(self) -> None:
        self._strategies: dict[str, PricingStrategy] = {
            "Delta": DeltaPricingStrategy(),
            "United": UnitedPricingStrategy(),
            "Southwest": SouthwestPricingStrategy(),
        }

    def get_strategy(self, airline: str) -> PricingStrategy:
        try:
            return self._strategies[airline]
        except KeyError as error:
            raise ValueError(f"invalid strategy request: {airline}") from error


class TicketPricingService:
    def __init__(self) -> None:
        self._operating_cost_calculator = OperatingCostCalculator()
        self._strategy_factory = StrategyFactory()

    def calculate(self, ticket_request: TicketRequest) -> Decimal:
        operating_cost = self._operating_cost_calculator.calculate(ticket_request)
        strategy = self._strategy_factory.get_strategy(ticket_request.airline)
        price = strategy.calculate(ticket_request, operating_cost)
        return price.quantize(CENT, rounding=ROUND_HALF_UP)

    def calculate_all(self, lines: Iterable[str]) -> Iterator[Decimal]:
        for line in lines:
            yield self.calculate(TicketRequest.parse(line))


def main() -> None:
    lines = ["United 150.0 Premium", "Delta 60.0 Business", "Southwest 1000.0 Economy"]
    expected_prices = [Decimal("152.50"), Decimal("95.00"), Decimal("1000.00")]
    service = TicketPricingService()
    for price, expected_price in zip(
        service.calculate_all(lines), expected_prices, strict=True
    ):
        assert expected_price == price
        print(f"{price:.2f}")


if __name__ == "__main__":
    main()
